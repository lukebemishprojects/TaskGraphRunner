package dev.lukebemish.taskgraphrunner.runtime;

import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;

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

    private final LockManager lockManager;

    private final Map<String, Task> tasks = new HashMap<>();
    private final Map<String, Path> artifacts = new HashMap<>();

    public Invocation(Path cacheDirectory) throws IOException {
        this.cacheDirectory = cacheDirectory;
        this.lockManager = new LockManager(cacheDirectory.resolve("locks"));
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
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
        return taskDirectory(taskName).resolve(contentsHash+"."+outputName+"."+outputType);
    }

    @Override
    public Path taskDirectory(String taskName) {
        var task = getTask(taskName);
        MessageDigest digestReference;
        try {
            digestReference = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashReference(RecordedInput.ByteConsumer.of(digestReference), this);
        var hash = HexFormat.of().formatHex(digestReference.digest());
        return cacheDirectory.resolve("results").resolve(taskName+"."+hash);
    }

    @Override
    public Path taskStatePath(String taskName) {
        var task = getTask(taskName);
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
        return taskDirectory(taskName).resolve(contentsHash+".json");
    }

    @Override
    public Path taskWorkingDirectory(String taskName) {
        var task = getTask(taskName);
        MessageDigest digestContents;
        try {
            digestContents = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashContents(RecordedInput.ByteConsumer.of(digestContents), this);
        var contentsHash = HexFormat.of().formatHex(digestContents.digest());
        return taskDirectory(taskName).resolve(contentsHash);
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

    @Override
    public LockManager lockManager() {
        return lockManager;
    }

    public void execute(Map<Output, Path> results) {
        Map<String, Map<String, Path>> tasks = new LinkedHashMap<>();
        for (var entry : results.entrySet()) {
            var map = tasks.computeIfAbsent(entry.getKey().taskName(), k -> new LinkedHashMap<>());
            map.put(entry.getKey().name(), entry.getValue());
        }
        try (var ignored = locks(tasks.keySet())) {
            tasks.entrySet().parallelStream().forEach(entry -> {
                var task = getTask(entry.getKey());
                task.execute(this, entry.getValue());
            });
        }
    }
}
