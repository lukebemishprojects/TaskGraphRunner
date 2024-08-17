package dev.lukebemish.taskgraphrunner.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadDistributionTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadJsonTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadManifestTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadMappingsTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.ListClasspathTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.SplitClassesResourcesTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.ToolTask;
import dev.lukebemish.taskgraphrunner.runtime.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public abstract class Task implements RecordedInput {
    private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String name;

    public Task(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public abstract List<TaskInput> inputs();

    public abstract Map<String, String> outputTypes();

    private volatile byte[] referenceHash;
    private volatile byte[] contentsHash;

    private boolean executed;
    private boolean running;

    synchronized void execute(Context context, Map<String, Path> outputDestinations) {
        // This operation is NOT locked; locking is handled for a full invocation execution instead
        execute(context);
        for (var entry : outputDestinations.entrySet()) {
            var outputPath = context.taskOutputPath(name(), entry.getKey());
            try {
                Files.copy(outputPath, entry.getValue(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private synchronized void execute(Context context) {
        // This operation is NOT locked; locking is handled for a full invocation execution instead

        if (executed) {
            return;
        }
        if (running) {
            throw new IllegalStateException("Task `"+name+"` has a circular dependency");
        }
        running = true;
        // Run prerequisite tasks
        inputs().parallelStream().forEach(input ->
            input.dependencies().parallelStream().forEach(dependency ->
                context.getTask(dependency).execute(context)
            )
        );
        var statePath = context.taskStatePath(name());
        try {
            Files.createDirectories(statePath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (Files.exists(statePath)) {
            try (var reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
                JsonObject existingState = GSON.fromJson(reader, JsonObject.class);
                JsonElement existingInputState = existingState.get("inputs");
                var targetHashes = existingState.get("hashes").getAsJsonObject();
                var lastExecutedJson = existingState.get("lastExecuted");
                long lastExecuted = 0;
                if (lastExecutedJson != null) {
                    lastExecuted = lastExecutedJson.getAsLong();
                }
                boolean allOutputsMatch = true;
                for (var output : outputTypes().keySet()) {
                    var oldHashElement = targetHashes.get(output);
                    if (oldHashElement == null || !oldHashElement.isJsonPrimitive() || !oldHashElement.getAsJsonPrimitive().isString()) {
                        allOutputsMatch = false;
                        break;
                    }
                    var oldHash = oldHashElement.getAsString();
                    var outputPath = context.taskOutputPath(name(), output);
                    if (!Files.exists(outputPath)) {
                        allOutputsMatch = false;
                        break;
                    }
                    var hash = HashUtils.hash(outputPath);
                    if (!hash.equals(oldHash)) {
                        allOutputsMatch = false;
                        break;
                    }
                }
                if (allOutputsMatch && upToDate(lastExecuted, context)) {
                    JsonElement newInputState = recordedValue(context);
                    if (newInputState.equals(existingInputState)) {
                        updateState(existingState, context);
                        executed = true;
                        running = false;
                        LOGGER.info("Task `"+name+"` is up-to-date");
                        return;
                    }
                }
            } catch (Exception e) {
                // something went wrong -- let's log it, then keep going:
                // TODO: log it
            }
        }
        // Something was not up-to-date -- so we run everything
        LOGGER.info("Starting task `"+name+"`.");
        run(context);
        saveState(context);
        LOGGER.info("Finished task `"+name+"`.");
        executed = true;
        running = false;
    }

    private void updateState(JsonObject oldState, Context context) {
        oldState.add("lastAccessed", new JsonPrimitive(System.currentTimeMillis()));
        try (var writer = Files.newBufferedWriter(context.taskStatePath(name()), StandardCharsets.UTF_8)) {
            GSON.toJson(oldState, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveState(Context context) {
        var statePath = context.taskStatePath(name());
        var inputState = recordedValue(context);
        JsonObject outputHashes = new JsonObject();
        for (var output : outputTypes().keySet()) {
            var outputPath = context.taskOutputPath(name(), output);
            if (Files.exists(outputPath)) {
                try {
                    var hash = HashUtils.hash(outputPath);
                    outputHashes.addProperty(output, hash);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException("Output file for `"+output+"`not found after task `"+name+"` completed");
            }
        }
        JsonObject state = new JsonObject();
        state.add("inputs", inputState);
        state.add("hashes", outputHashes);
        var currentTime = System.currentTimeMillis();
        state.add("lastAccessed", new JsonPrimitive(currentTime));
        state.add("lastExecuted", new JsonPrimitive(currentTime));
        try (var writer = Files.newBufferedWriter(statePath, StandardCharsets.UTF_8)) {
            GSON.toJson(state, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void hashReference(ByteConsumer digest, Context context) {
        if (referenceHash == null) {
            synchronized (this) {
                if (referenceHash == null) {
                    var stream = new ByteArrayOutputStream();
                    var consumer = ByteConsumer.of(stream);
                    consumer.update(name().getBytes(StandardCharsets.UTF_8));
                    consumer.update(getClass().getName().getBytes(StandardCharsets.UTF_8));
                    for (TaskInput input : inputs()) {
                        consumer.update(input.name().getBytes(StandardCharsets.UTF_8));
                        input.hashReference(consumer, context);
                    }
                    referenceHash = stream.toByteArray();
                }
            }
        }
        digest.update(referenceHash);
    }

    @Override
    public void hashContents(ByteConsumer digest, Context context) {
        if (contentsHash == null) {
            synchronized (this) {
                if (contentsHash == null) {
                    var stream = new ByteArrayOutputStream();
                    var consumer = ByteConsumer.of(stream);
                    consumer.update(name().getBytes(StandardCharsets.UTF_8));
                    consumer.update(getClass().getName().getBytes(StandardCharsets.UTF_8));
                    for (TaskInput input : inputs()) {
                        for (var dependency : input.dependencies()) {
                            if (!context.getTask(dependency).executed) {
                                throw new IllegalStateException("Dependency `"+dependency+"` of task `"+name()+"` has not been executed but was requested");
                            }
                        }
                        consumer.update(input.name().getBytes(StandardCharsets.UTF_8));
                        input.hashContents(consumer, context);
                    }
                    contentsHash = stream.toByteArray();
                }
            }
        }
        digest.update(contentsHash);
    }

    @Override
    public JsonElement recordedValue(Context context) {
        JsonObject state = new JsonObject();
        state.addProperty("name", name());
        state.addProperty("type", getClass().getName());
        JsonArray inputs = new JsonArray();
        for (TaskInput input : inputs()) {
            JsonObject inputObject = new JsonObject();
            inputObject.addProperty("name", input.name());
            inputObject.add("value", input.recordedValue(context));
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                input.hashContents(ByteConsumer.of(digest), context);
                var hash = HexFormat.of().formatHex(digest.digest());
                inputObject.addProperty("key", hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            inputs.add(inputObject);
        }
        state.add("inputs", inputs);
        return state;
    }

    protected boolean upToDate(long lastExecuted, Context context) {
        return true;
    }

    public static Task task(TaskModel model, WorkItem workItem, Context context) {
        return switch (model) {
            case TaskModel.DownloadManifest downloadManifest -> new DownloadManifestTask(downloadManifest);
            case TaskModel.DownloadJson downloadJson -> new DownloadJsonTask(downloadJson, workItem, context);
            case TaskModel.DownloadDistribution downloadDistribution -> new DownloadDistributionTask(downloadDistribution, workItem, context);
            case TaskModel.DownloadMappings downloadMappings -> new DownloadMappingsTask(downloadMappings, workItem, context);
            case TaskModel.SplitClassesResources splitClassesResources -> new SplitClassesResourcesTask(splitClassesResources, workItem, context);
            case TaskModel.Tool tool -> new ToolTask(tool, workItem, context);
            case TaskModel.ListClasspath listClasspath -> new ListClasspathTask(listClasspath, workItem, context);
            default -> throw new UnsupportedOperationException("Not yet implemented");
        };
    }

    protected abstract void run(Context context);
}