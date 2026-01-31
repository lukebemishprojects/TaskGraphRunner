package dev.lukebemish.taskgraphrunner.runtime;

import java.nio.file.Path;
import java.util.Objects;

public record TaskOutput(String taskName, String name) {
    public Path resolvePath(Context context) {
        // Reassembles ZIP or the like if needed
        var path = Objects.requireNonNull(context.existingTaskOutput(context.getTask(taskName), name), "Output did not exist");
        var parts = path.getFileName().toString().split("\\.");
        if (parts.length > 1) {
            var extension = parts[parts.length - 1];
            if (!extension.equals(context.getTask(taskName).outputTypes().get(name)) && "binpb".equals(extension)) {
                return context.reassembleTaskOutput(context.getTask(taskName), name, path);
            }
        }
        return path;
    }
}
