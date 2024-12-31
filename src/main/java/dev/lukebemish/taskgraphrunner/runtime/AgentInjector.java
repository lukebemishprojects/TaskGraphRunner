package dev.lukebemish.taskgraphrunner.runtime;

import dev.lukebemish.taskgraphrunner.runtime.execution.ToolDaemonExecutor;
import dev.lukebemish.taskgraphrunner.runtime.util.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class AgentInjector {
    public static final Path INSTRUMENTATION_JAR;

    public static String makeArg() {
        return "-javaagent:"+INSTRUMENTATION_JAR.toAbsolutePath();
    }

    static {
        try {
            var workingDirectory = Files.createTempDirectory("taskgraphrunner");
            var tempFile = workingDirectory.resolve("instrumentation.jar");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    FileUtils.deleteRecursively(workingDirectory);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }));
            try (var stream = ToolDaemonExecutor.class.getResourceAsStream("/instrumentation.jar")) {
                Files.copy(Objects.requireNonNull(stream, "Could not find bundled agent to instrument tools"), tempFile);
            }
            INSTRUMENTATION_JAR = tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
