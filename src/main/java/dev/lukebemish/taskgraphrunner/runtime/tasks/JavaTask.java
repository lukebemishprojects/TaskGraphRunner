package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.runtime.AgentInjector;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.util.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public abstract class JavaTask extends Task {
    public JavaTask(TaskModel model) {
        super(model);
    }

    @Override
    protected void run(Context context) {
        var javaExecutablePath = ProcessHandle.current()
            .info()
            .command()
            .orElseThrow();

        var command = new ArrayList<String>();

        // Instrument the tool with the agent
        command.add(AgentInjector.makeArg());

        var workingDirectory = context.taskWorkingDirectory(this);
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        collectArguments(command, context, workingDirectory);

        var logFile = workingDirectory.resolve("log.txt");
        var argsFile = workingDirectory.resolve("args.txt");

        try {
            try (var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                writer.append("-".repeat(80)).append("\n\n");
                writer.append("Command-Line:\n");
                writer.append(" - ").append(javaExecutablePath).append("\n");
                for (String s : command) {
                    writer.append(" - ").append(s).append("\n");
                }
                writer.append("-".repeat(80)).append("\n\n");
            }

            try (var writer = Files.newBufferedWriter(argsFile, StandardCharsets.UTF_8)) {
                for (var argument : command) {
                    writer.write(FileUtils.escapeForArgument(argument)+System.lineSeparator());
                }
            }

            var process = new ProcessBuilder()
                .directory(workingDirectory.toFile())
                .command(List.of(javaExecutablePath, "@args.txt"))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Tool failed with exit code " + exitCode + ", see log file at "+logFile.toAbsolutePath()+" for details");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute tool", e);
        }
    }

    protected abstract void collectArguments(ArrayList<String> command, Context context, Path workingDirectory);
}
