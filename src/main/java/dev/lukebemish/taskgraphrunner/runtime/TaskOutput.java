package dev.lukebemish.taskgraphrunner.runtime;

import java.nio.file.Path;
import java.util.Objects;

public record TaskOutput(String taskName, String name) {
    public Path getPath(Context context) {
        return Objects.requireNonNull(context.existingTaskOutput(context.getTask(taskName), name), "Output did not exist");
    }
}
