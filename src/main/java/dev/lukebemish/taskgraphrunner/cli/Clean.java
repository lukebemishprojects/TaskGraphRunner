package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@CommandLine.Command(name = "clean", mixinStandardHelpOptions = true, description = "Clean up old outputs")
public class Clean implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Clean.class);

    private final Main main;

    @CommandLine.Option(names = "--lock-duration", description = "Time to keep lock files for, in days.", arity = "*")
    int lockDuration = 1;

    @CommandLine.Option(names = "--output-duration", description = "Time to keep task outputs for, in days.", arity = "*")
    int outputDuration = 30;

    @CommandLine.Option(names = "--asset-duration", description = "Time to keep downloaded assets for, in days.", arity = "*")
    int assetDuration = 30;

    Clean(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        try {
            LockManager lockManager = new LockManager(main.cacheDir.resolve("locks"));
            if (assetDuration >= 0) {
                cleanAssets(lockManager);
            }
            if (outputDuration >= 0) {
                cleanTaskOutputs(lockManager);
            }
            if (lockDuration >= 0) {
                lockManager.cleanOldLocks(lockDuration);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void cleanAssets(LockManager lockManager) {
        FileTime outdated = FileTime.from(Instant.now().minus(assetDuration, ChronoUnit.DAYS));

        if (Files.isDirectory(main.cacheDir.resolve("assets").resolve("indexes"))) {
            List<String> assetIndexes;
            try (var stream = Files.list(main.cacheDir.resolve("assets").resolve("indexes"))) {
                assetIndexes = stream
                    .map(it -> it.getFileName().toString())
                    .filter(it -> it.endsWith(".json"))
                    .toList();
            } catch (IOException e) {
                LOGGER.error("Issue listing asset indexes", e);
                return;
            }
            try (var ignored = lockManager.locks(assetIndexes)) {
                record IndexInfo(String name, FileTime lastAccess, Set<String> hashes) {}
                var indexes = new ArrayList<IndexInfo>();
                for (String index : assetIndexes) {
                    Path path = main.cacheDir.resolve("assets").resolve("indexes").resolve(index);
                    try {
                        if (Files.exists(path) && Files.isRegularFile(path)) {
                            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                            Set<String> hashes = new HashSet<>();
                            try (var reader = Files.newBufferedReader(path)) {
                                var objects = JsonUtils.GSON.fromJson(reader, JsonObject.class).getAsJsonObject("objects");
                                for (var entry : objects.entrySet()) {
                                    hashes.add(entry.getValue().getAsJsonObject().getAsJsonPrimitive("hash").getAsString());
                                }
                            }
                            indexes.add(new IndexInfo(index, attributes.lastAccessTime(), hashes));
                        }
                    } catch (IOException e) {
                        LOGGER.error("Issue reading asset index {}", index, e);
                        return;
                    }
                }
                var toDelete = indexes.stream()
                    .filter(info -> info.lastAccess.compareTo(outdated) < 0)
                    .flatMap(info -> info.hashes.stream())
                    .collect(Collectors.toCollection(HashSet::new));
                indexes.stream()
                    .filter(info -> info.lastAccess.compareTo(outdated) >= 0)
                    .forEach(info -> toDelete.removeAll(info.hashes));

                var deletedAssets = new AtomicInteger();
                var deletedIndexes = new AtomicInteger();
                for (String hash : toDelete) {
                    Path asset = main.cacheDir.resolve("assets").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                    if (Files.exists(asset) && Files.isRegularFile(asset)) {
                        try {
                            Files.delete(asset);
                            deletedAssets.incrementAndGet();
                        } catch (IOException e) {
                            LOGGER.error("Issue deleting asset {}", asset, e);
                        }
                    }
                }
                for (var info : indexes) {
                    if (info.lastAccess.compareTo(outdated) < 0) {
                        Path path = main.cacheDir.resolve("assets").resolve("indexes").resolve(info.name);
                        try {
                            Files.delete(path);
                            deletedIndexes.incrementAndGet();
                        } catch (IOException e) {
                            LOGGER.error("Issue deleting asset index {}", path, e);
                        }
                    }
                }
                if (deletedIndexes.get() > 0) {
                    LOGGER.info("Deleted {} outdated asset indexes", deletedIndexes.get());
                }
                if (deletedAssets.get() > 0) {
                    LOGGER.info("Deleted {} unused assets", deletedAssets.get());
                }
            }
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
            dirs.forEach(dir -> deleteOutdated(lockManager, dir, outdated, deletedOutputs));
        } catch (IOException e) {
            LOGGER.error("Issue deleting output directories", e);
        }
        if (deletedOutputs.get() > 0) {
            LOGGER.info("Deleted {} outdated output files", deletedOutputs.get());
        }
    }

    private static void deleteOutdated(LockManager lockManager, Path dir, FileTime outdated, AtomicInteger deletedOutputs) {
        if (Files.isDirectory(dir)) {
            try (var ignored = lockManager.lock(dir.getFileName().toString())) {
                try (var files = Files.list(dir)) {
                    files.forEach(it -> {
                            try {
                                BasicFileAttributes attributes = Files.readAttributes(it, BasicFileAttributes.class);
                                if (attributes.isRegularFile() && attributes.lastAccessTime().compareTo(outdated) < 0) {
                                    Files.delete(it);
                                    deletedOutputs.incrementAndGet();
                                } else if (attributes.isDirectory()) {
                                    deleteOutdated(lockManager, it, outdated, deletedOutputs);
                                }
                            } catch (IOException e) {
                                LOGGER.error("Issue while cleaning output file {} in {}", it.getFileName(), dir.getFileName(), e);
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
    }
}
