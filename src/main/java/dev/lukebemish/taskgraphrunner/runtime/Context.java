package dev.lukebemish.taskgraphrunner.runtime;

import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public interface Context {
    Path taskOutputPath(Task task, String outputName);

    Path taskOutputMarkerPath(Task task, String outputName);

    Path pathFromHash(String hash, String outputType);

    Path existingTaskOutput(Task task, String outputName);

    Path taskStatePath(Task task);

    Path taskDirectory(Task task);

    Path taskWorkingDirectory(Task task);

    Task getTask(String name);

    Path findArtifact(String notation);

    ArtifactManifest artifactManifest();

    LockManager lockManager();

    Path transformCachePath(int version);

    boolean useCached();

    AssetDownloadOptions assetOptions();

    Future<?> submit(Runnable runnable);

    <T> Future<T> submit(Callable<T> callable);

    default <T> void execute(Collection<T> objects, Consumer<T> action) {
        var futures = new ArrayList<Future<?>>();
        for (var task : objects) {
            futures.add(submit(() -> action.accept(task)));
        }
        for (var future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    Map<String, Output> aliases();

    final class AssetDownloadOptions {
        private final Path assetRoot;
        private final List<Path> potentialLauncherRoots;
        private final boolean redownloadAssets;

        private AssetDownloadOptions(Path assetRoot, List<Path> potentialLauncherRoots, boolean redownloadAssets) {
            this.assetRoot = assetRoot;
            this.potentialLauncherRoots = potentialLauncherRoots;
            this.redownloadAssets = redownloadAssets;
        }

        public Path assetRoot() {
            return assetRoot;
        }

        public List<Path> potentialLauncherRoots() {
            return potentialLauncherRoots;
        }

        public boolean redownloadAssets() {
            return redownloadAssets;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private Path assetRoot;
            private List<Path> potentialLauncherRoots = List.of();
            private boolean redownloadAssets = false;

            private Builder() {}

            public Builder assetRoot(Path assetRoot) {
                this.assetRoot = assetRoot;
                return this;
            }

            public Builder potentialLauncherRoots(List<Path> potentialLauncherRoots) {
                this.potentialLauncherRoots = potentialLauncherRoots;
                return this;
            }

            public Builder redownloadAssets(boolean redownloadAssets) {
                this.redownloadAssets = redownloadAssets;
                return this;
            }

            public AssetDownloadOptions build() {
                return new AssetDownloadOptions(Objects.requireNonNull(assetRoot), List.copyOf(Objects.requireNonNull(potentialLauncherRoots)), redownloadAssets);
            }
        }
    }
}
