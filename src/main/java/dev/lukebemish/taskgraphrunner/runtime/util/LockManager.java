package dev.lukebemish.taskgraphrunner.runtime.util;

import dev.lukebemish.taskgraphrunner.runtime.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

public class LockManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(LockManager.class);
    private static final String ROOT_LOCK = "root";

    private final Path lockDirectory;

    private static final Map<String, Semaphore> parallelLocks = new ConcurrentHashMap<>();

    private static int findParallelism(String key) {
        int result = Integer.getInteger("dev.lukebemish.taskgraphrunner.parallelism." + key, 1);
        if (result < 1) {
            throw new IllegalArgumentException("Property dev.lukebemish.taskgraphrunner.parallelism."+key+" must be positive");
        }
        return result;
    }

    public void enforcedParallelism(Context context, String key, Runnable action) {
        var parallelism = findParallelism(key);
        var semaphore = parallelLocks.computeIfAbsent(key, k -> new Semaphore(parallelism));
        try {
            semaphore.acquire();
            try {
                try (var ignored = lockWithCount(context, "parallelism." + key, parallelism)) {
                    action.run();
                }
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public LockManager(Path lockDirectory) throws IOException {
        Files.createDirectories(lockDirectory);
        this.lockDirectory = lockDirectory;
    }

    private Path getLockFile(String key) {
        return lockDirectory.resolve(key + ".lock");
    }

    public Locks locks(List<String> keys) {
        try (var ignored = lock(ROOT_LOCK)) {
            var locks = keys.stream().map(this::lock).toList();
            return new Locks(locks);
        }
    }

    private sealed interface ThreadState {
        record WithThread(Thread thread) implements ThreadState {}
        enum NoThread implements ThreadState {
            STOPPED
        }
    }

    public Lock lockWithCount(Context context, String key, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Cannot lock with less than one option!");
        }
        AtomicReference<Lock> lock = new AtomicReference<>();
        List<Exception> exceptions = new ArrayList<>();
        @SuppressWarnings("unchecked") AtomicReference<ThreadState>[] states = new AtomicReference[count];
        for (int i = 0; i < count; i++) {
            states[i] = new AtomicReference<>(null);
        }
        context.execute(IntStream.range(0, count).boxed().toList(), i -> {
            try {
                boolean[] stopped = new boolean[1];
                states[i].updateAndGet(state -> {
                    if (state == ThreadState.NoThread.STOPPED) {
                        stopped[0] = true;
                        return state;
                    }
                    return new ThreadState.WithThread(Thread.currentThread());
                });
                if (stopped[0]) {
                    return;
                }
                var found = lock(key + "." + i);
                lock.updateAndGet(existing -> {
                    if (existing != null) {
                        existing.close();
                    }
                    for (int j = 0; j < count; j++) {
                        states[j].updateAndGet(state -> {
                            if (state instanceof ThreadState.WithThread withThread) {
                                withThread.thread().interrupt();
                            }
                            return ThreadState.NoThread.STOPPED;
                        });
                    }
                    return found;
                });
            } catch (RuntimeException e) {
                synchronized (exceptions) {
                    exceptions.add(e);
                }
            }
        });
        if (!exceptions.isEmpty()) {
            var e = new RuntimeException("Failed to acquire lock", exceptions.getFirst());
            for (int i = 1; i < exceptions.size(); i++) {
                e.addSuppressed(exceptions.get(i));
            }
            throw e;
        }
        return lock.get();
    }

    public Lock lock(String key) {
        var lockFile = getLockFile(key);
        LOGGER.debug("Acquiring lock on {} at {}", key, lockFile);

        // Try 5 times to get a file channel -- this doesn't block anything yet
        FileChannel channel = null;
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                break;
            } catch (AccessDeniedException e) {
                last = e;
                try {
                    // Wait one second, try again
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            } catch (IOException e) {
                last = e;
                break;
            }
        }
        if (channel == null) {
            throw new UncheckedIOException("Failed to create lock-file " + lockFile + " for key "+key, last);
        }

        // Now we try to get a lock on the file which will block other precesses
        FileLock fileLock;
        long startTime = System.currentTimeMillis();
        while (true) {
            try {
                fileLock = channel.tryLock();
                if (fileLock != null) {
                    break;
                }
            } catch (OverlappingFileLockException ignored) {
                // The lock is held by this process already, in another thread
            } catch (IOException e) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }

            try {
                if (System.currentTimeMillis() - startTime > 1000 * 60 * 5) {
                    // If we've waited more than two minutes, fail
                    throw new RuntimeException("Failed to acquire lock on " + lockFile +" for key "+key+"; timed out after 5 minutes");
                }
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        LOGGER.debug("Acquired lock on {} at {}", key, lockFile);

        return new Lock(fileLock, key);
    }

    public void cleanOldLocks(int lockDuration) {
        FileTime outdated = FileTime.from(Instant.now().minus(lockDuration, ChronoUnit.DAYS));

        var filesDeleted = new AtomicInteger(0);
        try (var stream = Files.list(lockDirectory)) {
            stream
                .filter(it -> it.getFileName().toString().endsWith(".lock"))
                .filter(it -> {
                    try {
                        var attributes = Files.readAttributes(it, BasicFileAttributes.class);
                        return attributes.isRegularFile() && attributes.lastModifiedTime().compareTo(outdated) < 0;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(it -> {
                    try {
                        Files.delete(it);
                        filesDeleted.incrementAndGet();
                    } catch (IOException e) {
                        LOGGER.error("Failed to delete outdated lock file {}", it, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("Issue deleting lock files", e);
        }

        if (filesDeleted.get() > 0) {
            LOGGER.info("Deleted {} outdated lock files", filesDeleted.get());
        }
    }

    public sealed interface LockLike extends AutoCloseable {
        @Override
        void close();
    }

    public static final class Locks implements LockLike {
        private final List<? extends LockLike> locks;

        private Locks(List<? extends LockLike> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            for (var lock : locks) {
                lock.close();
            }
        }
    }

    public static final class Lock implements LockLike {
        private final FileLock fileLock;
        private final String key;

        private Lock(FileLock fileLock, String key) {
            this.fileLock = fileLock;
            this.key = key;
        }

        @Override
        public void close() {
            LOGGER.debug("Releasing lock on {}", key);
            try {
                fileLock.release();
            } catch (IOException e) {
                LOGGER.error("Failed to release lock on {}", fileLock.channel().toString(), e);
            }
            try {
                fileLock.channel().close();
            } catch (IOException ignored) {
            }
        }
    }
}
