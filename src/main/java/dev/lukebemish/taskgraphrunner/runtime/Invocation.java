package dev.lukebemish.taskgraphrunner.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import dev.lukebemish.taskgraphrunner.runtime.zips.PiecewiseZips;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

public class Invocation implements Context, AutoCloseable {
    private static final ThreadFactory THREAD_FACTORY = Thread.ofVirtual().name("TaskGraphRunner-", 1).factory();

    private final Path cacheDirectory;

    private final LockManager lockManager;

    private final Map<String, Task> tasks = new HashMap<>();
    private final Map<String, Output> aliases;
    private final List<ArtifactManifest> artifactManifests = new ArrayList<>();
    private final ArtifactManifest artifactManifest = ArtifactManifest.delegating(artifactManifests);
    private final boolean useCached;
    private final AssetDownloadOptions assetOptions;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(THREAD_FACTORY);

    public Invocation(Path cacheDirectory, Map<String, Output> aliases, AssetDownloadOptions assetDownloadOptions, boolean useCached) throws IOException {
        this.cacheDirectory = cacheDirectory;
        this.lockManager = new LockManager(cacheDirectory.resolve("locks"));
        this.aliases = aliases;
        this.useCached = useCached;
        this.assetOptions = assetDownloadOptions;
    }

    @Override
    public Map<String, Output> aliases() {
        return aliases;
    }

    public void addTask(Task task) {
        tasks.put(task.name(), task);
    }

    public void artifactManifest(ArtifactManifest manifest) {
        artifactManifests.add(manifest);
    }

    @Override
    public Path taskOutputPath(Task task, String outputName) {
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+task.name()+"`");
        }
        var contentsHash = contentsHash(task);
        return taskDirectory(task).resolve(contentsHash+"."+outputName+"."+task.outputId()+"."+outputType);
    }

    @Override
    public Path taskOutputMarkerPath(Task task, String outputName) {
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+task.name()+"`");
        }
        var contentsHash = contentsHash(task);
        return taskDirectory(task).resolve(contentsHash+"."+outputName+"."+task.outputId()+"."+outputType+".txt");
    }

    private static final Set<String> CAN_REASSEMBLE = Set.of(
        "zip",
        "jar"
    );

    public String contentAddressForTaskOutput(Task task, String outputName) {
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+task.name()+"`");
        }
        return task.getContentAddress(outputName);
    }

    @Override
    public Path contentAddressedTaskOutput(Task task, String outputName) {
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+task.name()+"`");
        }
        var contentsHash = contentsHash(task);
        var markerPath = taskDirectory(task).resolve(contentsHash+"."+outputName+"."+task.outputId()+"."+outputType+".txt");
        if (!Files.exists(markerPath)) {
            return null;
        }
        try {
            var contents = Files.readString(markerPath, StandardCharsets.UTF_8);
            if (contents.contains("/")) {
                var parts = contents.split("/");
                task.setContentAddress(outputName, parts[0]);
                if (CAN_REASSEMBLE.contains(parts[1])) {
                    var prefix = parts[0].substring(0, 2);
                    return contentAddressableDirectory().resolve(prefix).resolve(parts[0] + "." + outputType  + ".binpb");
                } else {
                    // Requires reassembly we cannot perform
                    return null;
                }
            }
            task.setContentAddress(outputName, contents);
            var prefix = contents.substring(0, 2);
            return contentAddressableDirectory().resolve(prefix).resolve(contents + "." + outputType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final Map<String, Path> reassemblyCache = new ConcurrentHashMap<>();
    private final Path reassemblyCachePath = Files.createTempDirectory("taskgraphrunner-reassembly-");

    public String storeTaskOutput(Task task, String output) throws IOException {
        var outputPath = taskOutputPath(task, output);
        var outputType = task.outputTypes().get(output);
        var hash = contentAddressForTaskOutput(task, output);
        return switch (outputType) {
            case "zip", "jar" -> {
                var outPath = pathFromHash(hash, outputType + ".binpb");
                Files.createDirectories(outPath.getParent());
                new PiecewiseZips(this).disassemble(outputPath, outPath);
                reassemblyCache.compute(task.name() + "/" + output + "." + outputType, (k, v) -> {
                    var outputPathCache = reassemblyCachePath.resolve(k);
                    try {
                        Files.createDirectories(outputPathCache.getParent());
                        Files.move(outputPath, outputPathCache, StandardCopyOption.ATOMIC_MOVE);
                        return outputPathCache;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                yield hash + "/" + outputType;
            }
            default -> {
                var outPath = pathFromHash(hash, task.outputTypes().get(output));
                Files.createDirectories(outPath.getParent());
                // This is atomic because locking here is less sensible
                Files.move(outputPath, outPath, StandardCopyOption.ATOMIC_MOVE);
                yield hash;
            }
        };
    }

    public Path reassembleTaskOutput(Task task, String outputName, Path reassemblyInfo) {
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+task.name()+"`");
        }
        return reassemblyCache.computeIfAbsent(task.name() + "/" + outputName + "." + outputType, k -> {
            var outputPath = reassemblyCachePath.resolve(k);
            try {
                Files.createDirectories(outputPath.getParent());
                switch (outputType) {
                    case "zip", "jar" -> {
                        new PiecewiseZips(this).assemble(outputPath, reassemblyInfo);
                    }
                    default -> {
                        throw new IllegalArgumentException("Cannot reassemble output of type `" + outputType + "` for task `" + task.name() + "`");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return outputPath;
        });
    }

    @Override
    public Path pathFromHash(String hash, String outputType) {
        var prefix = hash.substring(0, 2);
        return contentAddressableDirectory().resolve(prefix).resolve(hash + "." + outputType);
    }

    private String contentsHash(Task task) {
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        return HexFormat.of().formatHex(digestContents.digest());
    }

    private Path contentAddressableDirectory() {
        return cacheDirectory.resolve("objects");
    }

    @Override
    public Path taskDirectory(Task task) {
        MessageDigest digestReference;
        try {
            digestReference = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashReference(RecordedInput.ByteConsumer.of(digestReference), this);
        var hash = HexFormat.of().formatHex(digestReference.digest());
        return cacheDirectory.resolve("results").resolve(task.type() +"."+hash);
    }

    @Override
    public Path taskStatePath(Task task) {
        var contentsHash = contentsHash(task);
        return taskDirectory(task).resolve(contentsHash+".json");
    }

    @Override
    public Path taskWorkingDirectory(Task task) {
        var contentsHash = contentsHash(task);
        return taskDirectory(task).resolve(contentsHash);
    }

    @Override
    public Task getTask(String name) {
        var task = tasks.get(name);
        if (task == null) {
            throw new IllegalArgumentException("No such task `"+name+"`");
        }
        return task;
    }

    @Override
    public Path findArtifact(String notation) {
        return artifactManifest.findArtifact(notation);
    }

    @Override
    public ArtifactManifest artifactManifest() {
        return artifactManifest;
    }

    @Override
    public LockManager lockManager() {
        return lockManager;
    }

    @Override
    public Path transformCachePath(int version) {
        return cacheDirectory.resolve("transforms."+version);
    }

    @Override
    public boolean useCached() {
        return this.useCached;
    }

    @Override
    public AssetDownloadOptions assetOptions() {
        return this.assetOptions;
    }

    public Future<?> submit(Runnable runnable) {
        return executor.submit(runnable);
    }

    public <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    public void execute(Map<Output, Path> results, @Nullable Path taskRecordJson) {
        Map<String, Map<String, Path>> tasks = new LinkedHashMap<>();
        for (var entry : results.entrySet()) {
            var taskName = entry.getKey().taskName();
            var map = tasks.computeIfAbsent(taskName, k -> new LinkedHashMap<>());
            map.put(entry.getKey().name(), entry.getValue());
        }
        Task.executeTasks(this, tasks);
        if (taskRecordJson != null) {
            JsonObject executed = new JsonObject();
            for (var task : this.tasks.values()) {
                if (task.isExecuted()) {
                    JsonObject singleTask = new JsonObject();
                    singleTask.addProperty("type", task.type());
                    JsonArray outputs = new JsonArray();
                    singleTask.addProperty("state", taskStatePath(task).toAbsolutePath().toString());
                    for (var output : task.outputTypes().entrySet()) {
                        var existingPath = contentAddressedTaskOutput(task, output.getKey());
                        var lastDot = existingPath.getFileName().toString().lastIndexOf('.');
                        if (lastDot != -1 && !"binpb".equals(output.getValue()) && "binpb".equals(existingPath.getFileName().toString().substring(lastDot + 1))) {
                            var reassemblyInfo = new JsonObject();
                            reassemblyInfo.addProperty("reassembles", existingPath.toAbsolutePath().toString());
                            reassemblyInfo.addProperty("type", output.getValue());
                            outputs.add(reassemblyInfo);
                        } else {
                            outputs.add(existingPath.toAbsolutePath().toString());
                        }
                    }
                    singleTask.add("outputs", outputs);
                    executed.add(task.name(), singleTask);
                }
            }
            try (var writer = Files.newBufferedWriter(taskRecordJson, StandardCharsets.UTF_8)) {
                JsonUtils.GSON.toJson(executed, writer);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
