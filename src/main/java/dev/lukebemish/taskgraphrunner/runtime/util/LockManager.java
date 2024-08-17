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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LockManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(LockManager.class);

    private final Path lockDirectory;

    public LockManager(Path lockDirectory) throws IOException {
        Files.createDirectories(lockDirectory);
        this.lockDirectory = lockDirectory;
    }

    private Path getLockFile(String key) {
        return lockDirectory.resolve(HashUtils.hash(key) + ".lock");
    }

    public Locks locks(List<String> keys) {
        var locks = keys.stream().map(this::lock).toList();
        return new Locks(locks);
    }

    public Lock lock(String key) {
        var lockFile = getLockFile(key);

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
            throw new UncheckedIOException("Failed to create lock-file " + lockFile, last);
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
                    throw new RuntimeException("Failed to acquire lock on " + lockFile +"; timed out after 5 minutes");
                }
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        LOGGER.debug("Acquired lock on {}", lockFile);

        return new Lock(fileLock);
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

    public static final class Locks implements AutoCloseable {
        private final List<Lock> locks;

        private Locks(List<Lock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            for (var lock : locks) {
                lock.close();
            }
        }
    }

    public static final class Lock implements AutoCloseable {
        private final FileLock fileLock;

        private Lock(FileLock fileLock) {
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
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
