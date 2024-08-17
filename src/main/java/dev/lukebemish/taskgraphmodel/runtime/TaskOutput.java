package dev.lukebemish.taskgraphmodel.runtime;

import java.nio.file.Path;

public record TaskOutput(String taskName, String name) {
    public Path getPath(Context context) {
        return context.taskOutputPath(taskName, name);
    }
}
