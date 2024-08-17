package dev.lukebemish.taskgraphmodel.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

public abstract class Task implements RecordedInput {
    private static final Gson GSON = new Gson();

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

    private boolean executed;

    synchronized void execute(Context context) {
        if (executed) {
            return;
        }
        var statePath = context.taskStatePath(name());
        if (Files.exists(statePath)) {
            try (var reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
                JsonObject existingState = GSON.fromJson(reader, JsonObject.class);
                JsonElement existingInputState = existingState.get("inputs");
                var targetHashes = existingState.get("hashes").getAsJsonObject();
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
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    try (var stream = Files.newInputStream(outputPath);
                         var dis = new DigestInputStream(stream, digest)) {
                        dis.readAllBytes();
                    }
                    var hash = String.format("%032x", new java.math.BigInteger(1, digest.digest()));
                    if (!hash.equals(oldHash)) {
                        allOutputsMatch = false;
                        break;
                    }
                }
                if (allOutputsMatch) {
                    JsonElement newInputState = inputState(context);
                    if (newInputState.equals(existingInputState)) {
                        executed = true;
                        return;
                    }
                }
            } catch (Exception e) {
                // something went wrong -- let's log it, then keep going:
                // TODO: log it
            }
        }
        // Run prerequisite tasks
        for (TaskInput input : inputs()) {
            for (String dependency : input.dependencies()) {
                context.getTask(dependency).execute(context);
            }
        }
        // Something was not up-to-date -- so we run everything
        run(context);
        executed = true;
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
        digest.update(name().getBytes(StandardCharsets.UTF_8));
        digest.update(getClass().getName().getBytes(StandardCharsets.UTF_8));
        for (TaskInput input : inputs()) {
            digest.update(input.name().getBytes(StandardCharsets.UTF_8));
            input.hashContents(digest, context);
        }
    }

    public JsonElement inputState(Context context) {
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
                var hash = String.format("%032x", new java.math.BigInteger(1, digest.digest()));
                inputObject.addProperty("key", hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            inputs.add(input.recordedValue(context));
        }
        state.add("inputs", inputs);
        return state;
    }

    abstract void run(Context context);
}
