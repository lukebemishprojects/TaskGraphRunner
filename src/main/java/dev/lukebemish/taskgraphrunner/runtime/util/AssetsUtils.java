package dev.lukebemish.taskgraphrunner.runtime.util;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public final class AssetsUtils {
    private static final String INDEX_FOLDER = "indexes";
    private static final String OBJECT_FOLDER = "objects";
    private static final String ASSETS_BASE_URL = "https://resources.download.minecraft.net/";
    private static final Logger LOGGER = LoggerFactory.getLogger(AssetsUtils.class);

    private static final String PARALLEL_DOWNLOADS_PROPERTY = "dev.lukebemish.taskgraphrunner.assets.parallel-downloads";

    // Sort from most to least indexes
    private static final Comparator<Target> ASSET_INDEX_COUNT_DESCENDING = Comparator.<Target>comparingInt(d -> d.indexes.size()).reversed();

    private record Target(Path directory, Set<String> indexes) {}

    private record DownloadTarget(String name, DownloadUtils.Spec spec, Path target) {}

    private static List<Target> findTargets(Context.AssetDownloadOptions options) throws IOException {
        var targets = new ArrayList<Target>();
        for (var launcher : options.potentialLauncherRoots()) {
            var indexes = new HashSet<String>();
            var root = launcher.resolve("assets");
            var indexesFolder = root.resolve(INDEX_FOLDER);
            var objectsFolder = root.resolve(OBJECT_FOLDER);
            if (Files.isDirectory(indexesFolder) && Files.isDirectory(objectsFolder)) {
                // Count the number of asset indices present to judge how viable this directory is
                try (var paths = Files.list(indexesFolder)) {
                    paths.map(f -> f.getFileName().toString())
                        .filter(f -> f.endsWith(".json"))
                        .map(f -> f.substring(0, f.length() - ".json".length()))
                        .forEach(indexes::add);
                }

                if (!indexes.isEmpty()) {
                    targets.add(new Target(root, indexes));
                }
            }
        }
        targets.sort(ASSET_INDEX_COUNT_DESCENDING);
        return targets;
    }

    private AssetsUtils() {}

    public static Path findOrDownloadIndexAndAssets(DownloadUtils.Spec spec, String assetIndexVersion, Context context) throws IOException {
        var assetOptions = context.assetOptions();
        if (!assetOptions.redownloadAssets()) {
            var launcherTargets = findTargets(assetOptions);
            for (var target : launcherTargets) {
                if (target.indexes.contains(assetIndexVersion)) {
                    LOGGER.info("Using existing assets directory "+target.directory);
                    return target.directory;
                }
            }
        }

        var targetPath = assetOptions.assetRoot().resolve("indexes").resolve(assetIndexVersion + ".json");
        if (!Files.exists(targetPath) || assetOptions.redownloadAssets()) {
            JsonObject json;
            try (var ignored = context.lockManager().lock("assetIndexes."+targetPath.getFileName())) {
                DownloadUtils.download(spec, targetPath);

                // Download the assets
                try (var reader = Files.newBufferedReader(targetPath)) {
                    json = JsonUtils.GSON.fromJson(reader, JsonObject.class);
                }

                var objectsPath = assetOptions.assetRoot().resolve(OBJECT_FOLDER);
                var objects = json.getAsJsonObject("objects");
                var targets = objects.asMap().values().stream()
                    .distinct() // The same object can be referenced multiple times
                    .map(entry -> {
                        var obj = entry.getAsJsonObject();
                        var hash = obj.getAsJsonPrimitive("hash").getAsString();
                        var size = obj.getAsJsonPrimitive("size").getAsLong();
                        var objectPath = objectsPath.resolve(hash.substring(0, 2)).resolve(hash);
                        var url = URI.create(ASSETS_BASE_URL + hash.substring(0, 2) + "/" + hash);
                        var objectSpec = new DownloadUtils.Spec.ChecksumAndSize(url, hash, "SHA-1", size);
                        return new DownloadTarget(hash, objectSpec, objectPath);
                    })
                    .toList();

                try (var ignored1 = context.lockManager().locks(targets.stream().map(t -> "assets." + t.target().getFileName().toString()).toList())) {
                    var futures = new ArrayList<Future<?>>();
                    var semaphore = new Semaphore(Integer.getInteger(PARALLEL_DOWNLOADS_PROPERTY, 8));
                    for (var target : targets) {
                        futures.add(context.submit(() -> {
                            try {
                                semaphore.acquire();
                                DownloadUtils.download(target.spec(), target.target());
                            } catch (IOException | InterruptedException e) {
                                LOGGER.error("Failed to download asset {} from {}", target.name(), target.spec().uri(), e);
                                throw new RuntimeException(e);
                            } finally {
                                semaphore.release();
                            }
                        }));
                    }
                    for (var future : futures) {
                        try {
                            future.get();
                        } catch (InterruptedException | ExecutionException e) {
                            // It failed; don't keep the bad index around
                            Files.deleteIfExists(targetPath);
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }

        return assetOptions.assetRoot();
    }
}
