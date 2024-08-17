package dev.lukebemish.taskgraphrunner.runtime;

import java.nio.file.Path;

public interface Context {
    Path taskOutputPath(String taskName, String outputName);

    Path taskStatePath(String taskName);

    Task getTask(String name);

    Path findArtifact(String notation);
}
