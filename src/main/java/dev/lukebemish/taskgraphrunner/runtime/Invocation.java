package dev.lukebemish.taskgraphrunner.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

public class Invocation implements Context, AutoCloseable {
    private static final ThreadFactory THREAD_FACTORY = Thread.ofVirtual().name("TaskGraphRunner-", 1).factory();

    private final Path cacheDirectory;

    private final LockManager lockManager;

    private final Map<String, Task> tasks = new HashMap<>();
    private final List<ArtifactManifest> artifactManifests = new ArrayList<>();
    private final ArtifactManifest artifactManifest = ArtifactManifest.delegating(artifactManifests);
    private final boolean useCached;
    private final AssetDownloadOptions assetOptions;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(THREAD_FACTORY);

    public Invocation(Path cacheDirectory, AssetDownloadOptions assetDownloadOptions, boolean useCached) throws IOException {
        this.cacheDirectory = cacheDirectory;
        this.lockManager = new LockManager(cacheDirectory.resolve("locks"));
        this.useCached = useCached;
        this.assetOptions = assetDownloadOptions;
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
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
        return taskDirectory(task).resolve(contentsHash+"."+outputName+"."+task.outputId()+"."+outputType);
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
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
        return taskDirectory(task).resolve(contentsHash+".json");
    }

    @Override
    public Path taskWorkingDirectory(Task task) {
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
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
                        outputs.add(taskOutputPath(task, output.getKey()).toAbsolutePath().toString());
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
