package dev.lukebemish.taskgraphrunner.runtime;

import java.nio.file.Path;
import java.util.Objects;

public record TaskOutput(String taskName, String name) {
    public Path resolvePath(Context context) {
        // TODO: avoid reassembly of unchanged dependencies if they are not needed
        // Reassembles ZIP or the like if needed
        var path = Objects.requireNonNull(context.existingTaskOutput(context.getTask(taskName), name), "Output did not exist");
        var lastDot = path.getFileName().toString().lastIndexOf('.');
        if (lastDot != -1) {
            var extension = path.getFileName().toString().substring(lastDot + 1);
            if (!"binpb".equals(context.getTask(taskName).outputTypes().get(name)) && "binpb".equals(extension)) {
                return context.reassembleTaskOutput(context.getTask(taskName), name, path);
            }
        }
        return path;
    }
}
