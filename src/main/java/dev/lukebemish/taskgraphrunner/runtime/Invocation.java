package dev.lukebemish.taskgraphrunner.runtime;

import dev.lukebemish.taskgraphrunner.model.Output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class Invocation implements Context {
    private final Path cacheDirectory;

    private final Map<String, Task> tasks = new HashMap<>();
    private final Map<String, Path> artifacts = new HashMap<>();

    public Invocation(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public void artifactManifest(Path manifest) {
        try (var reader = Files.newBufferedReader(manifest, StandardCharsets.ISO_8859_1)) {
            Properties properties = new Properties();
            properties.load(reader);
            for (var entry : properties.entrySet()) {
                var key = entry.getKey().toString();
                var value = entry.getValue().toString();
                artifacts.put(key, Path.of(value));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addTask(Task task) {
        tasks.put(task.name(), task);
    }

    @Override
    public Path taskOutputPath(String taskName, String outputName) {
        var task = getTask(taskName);
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+taskName+"`");
        }
        MessageDigest digestReference;
        MessageDigest contentsReference;
        try {
            digestReference = MessageDigest.getInstance("MD5");
            contentsReference = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashReference(RecordedInput.ByteConsumer.of(digestReference), this);
        task.hashContents(RecordedInput.ByteConsumer.of(contentsReference), this);
        var hash = HexFormat.of().formatHex(digestReference.digest());
        var contentsHash = HexFormat.of().formatHex(contentsReference.digest());
        return cacheDirectory.resolve("results").resolve(taskName+"."+hash).resolve(contentsHash+"."+outputName+"."+outputType);
    }

    public Path taskStatePath(String taskName) {
        var task = getTask(taskName);
        MessageDigest digestReference;
        MessageDigest digestContents;
        try {
            digestReference = MessageDigest.getInstance("MD5");
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashReference(RecordedInput.ByteConsumer.of(digestReference), this);
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var hash = HexFormat.of().formatHex(digestReference.digest());
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
        return cacheDirectory.resolve("results").resolve(taskName+"."+hash).resolve(contentsHash+".json");
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
        var artifact = artifacts.get(notation);
        if (artifact == null) {
            throw new IllegalArgumentException("No such artifact `"+notation+"`");
        }
        return artifact;
    }

    public void execute(Map<Output, Path> results) {
        Map<String, Map<String, Path>> tasks = new LinkedHashMap<>();
        for (var entry : results.entrySet()) {
            var map = tasks.computeIfAbsent(entry.getKey().taskName(), k -> new LinkedHashMap<>());
            map.put(entry.getKey().name(), entry.getValue());
        }
        for (var entry : tasks.entrySet()) {
            var task = getTask(entry.getKey());
            task.execute(this, entry.getValue());
        }
    }
}
