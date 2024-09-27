package dev.lukebemish.taskgraphrunner.runtime.util;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class LockManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(LockManager.class);
    private static final String ROOT_LOCK = "root";

    private final Path lockDirectory;

    private final Map<String, Lock> scopedLocks = new HashMap<>();
    private final Map<String, Integer> scopedLockCounts = new HashMap<>();
    private ReentrantLock scopedLock = new ReentrantLock();

    private void closeScopedLock(String key) {
        scopedLock.lock();
        try {
            var count = scopedLockCounts.compute(key, (k, v) -> v == null ? null : v - 1);
            if (count == null || count <= 0) {
                var lock = scopedLocks.remove(key);
                if (lock != null) {
                    lock.close();
                }
            }
        } finally {
            scopedLock.unlock();
        }
    }

    public LockLike managerScopedLock(String cacheKey) {
        scopedLock.lock();
        try {
            scopedLockCounts.compute(cacheKey, (key, count) -> count == null ? 1 : count + 1);
            scopedLocks.computeIfAbsent(cacheKey, this::lock);
        } finally {
            scopedLock.unlock();
        }
        return new ManagedLock(cacheKey);
    }

    public LockManager(Path lockDirectory) throws IOException {
        Files.createDirectories(lockDirectory);
        this.lockDirectory = lockDirectory;
    }

    private Path getLockFile(String key) {
        return lockDirectory.resolve(HashUtils.hash(key) + ".lock");
    }

    public Locks locks(List<String> keys) {
        try (var ignored = lock(ROOT_LOCK)) {
            var locks = keys.stream().map(this::lock).toList();
            return new Locks(locks);
        }
    }

    public Lock lockSingleFile(Path path) {
        return lock(HashUtils.hash(path.toAbsolutePath().toString()));
    }

    public Locks lockSingleFiles(List<Path> paths) {
        try (var ignored = lock(ROOT_LOCK)) {
            var locks = paths.stream().map(this::lockSingleFile).toList();
            return new Locks(locks);
        }
    }

    public void acquisition(Runnable runnable) {
        try (var ignored = lock(ROOT_LOCK)) {
            runnable.run();
        }
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

    public final class ManagedLock implements LockLike {
        private final String key;

        private ManagedLock(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            closeScopedLock(key);
        }
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
