package dev.lukebemish.taskgraphmodel.runtime;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Engine implements Context {
    private final Path cacheDirectory;

    private final Map<String, Task> tasks = new HashMap<>();

    public Engine(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    @Override
    public Path taskOutputPath(String taskName, String outputName) {
        var task = getTask(taskName);
        var outputType = task.outputTypes().get(outputName);
        if (outputType == null) {
            throw new IllegalArgumentException("No such output `"+outputName+"` for task `"+taskName+"`");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashReference(RecordedInput.ByteConsumer.of(digest), this);
        var hash = String.format("%032x", new java.math.BigInteger(1, digest.digest()));
        return cacheDirectory.resolve("results").resolve(taskName+"_"+hash+"_"+outputName+"."+outputType);
    }

    public Path taskStatePath(String taskName) {
        var task = getTask(taskName);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        task.hashReference(RecordedInput.ByteConsumer.of(digest), this);
        var hash = String.format("%032x", new java.math.BigInteger(1, digest.digest()));
        return cacheDirectory.resolve("results").resolve(taskName+"_"+hash+".json");
    }

    @Override
    public Task getTask(String name) {
        var task = tasks.get(name);
        if (task == null) {
            throw new IllegalArgumentException("No such task `"+name+"`");
        }
        return task;
    }

    public void execute(List<String> tasks) {
        for (var taskName : tasks) {
            var task = getTask(taskName);
            task.execute(this);
        }
    }
}
