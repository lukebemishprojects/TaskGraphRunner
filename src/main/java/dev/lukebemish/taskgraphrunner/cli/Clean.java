package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(name = "clean", mixinStandardHelpOptions = true, description = "Clean up old outputs")
public class Clean implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Clean.class);

    private final Main main;

    @CommandLine.Option(names = "--lock-duration", description = "Time to keep lock files for, in days.", arity = "*")
    int lockDuration = 1;

    @CommandLine.Option(names = "--output-duration", description = "Time to keep task outputs for, in days.", arity = "*")
    int outputDuration = 30;

    public Clean(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        try {
            LockManager lockManager = new LockManager(main.cacheDir.resolve("locks"));
            cleanTaskOutputs(lockManager);
            lockManager.cleanOldLocks(lockDuration);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void cleanTaskOutputs(LockManager lockManager) {
        FileTime outdated = FileTime.from(Instant.now().minus(outputDuration, ChronoUnit.DAYS));

        // We have to lock these to avoid issues if a task is running elsewhere
        if (!Files.exists(main.cacheDir.resolve("results"))) {
            // Nothing to clean
            return;
        }
        var deletedOutputs = new AtomicInteger();
        try (var dirs = Files.list(main.cacheDir.resolve("results"))) {
            dirs.forEach(dir -> {
                if (Files.isDirectory(dir)) {
                    try (var ignored = lockManager.lock(dir.getFileName().toString())) {
                        try (var files = Files.list(dir)) {
                            files
                                .filter(it -> {
                                    try {
                                        BasicFileAttributes attributes = Files.readAttributes(it, BasicFileAttributes.class);
                                        return attributes.isRegularFile() && attributes.lastAccessTime().compareTo(outdated) < 0;
                                    } catch (IOException e) {
                                        return false;
                                    }
                                })
                                .forEach(it -> {
                                    try {
                                        Files.delete(it);
                                        deletedOutputs.incrementAndGet();
                                    } catch (IOException e) {
                                        LOGGER.error("Failed to delete outdated output file {}", it, e);
                                    }
                                });
                        } catch (IOException e) {
                            LOGGER.error("Issue deleting output files in {}", dir.getFileName(), e);
                        }
                        try (var files = Files.list(dir)) {
                            if (files.findFirst().isEmpty()) {
                                try {
                                    Files.delete(dir);
                                } catch (IOException e) {
                                    LOGGER.error("Failed to delete empty output directory {}", dir, e);
                                }
                            }
                        } catch (IOException e) {
                            LOGGER.error("Issue deleting empty output directories", e);
                        }
                    }
                }
            });
        } catch (IOException e) {
            LOGGER.error("Issue deleting output directories", e);
        }
        if (deletedOutputs.get() > 0) {
            LOGGER.info("Deleted {} outdated output files", deletedOutputs.get());
        }
    }
}
