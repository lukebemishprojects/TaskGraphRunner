package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.execution.ToolDaemonExecutor;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DaemonExecutedToolTask extends Task {
    private final List<ArgumentProcessor.Arg> args;
    private final List<TaskInput> inputs;
    private final TaskInput.FileListInput classpath;
    private final TaskInput.@Nullable ValueInput mainClass;
    private final Map<String, String> outputExtensions;
    private final boolean classpathScopedJvm;

    public DaemonExecutedToolTask(TaskModel.DaemonExecutedTool model, WorkItem workItem, Context context) {
        super(model);

        this.outputExtensions = new HashMap<>();

        this.args = new ArrayList<>();
        ArgumentProcessor.processArgs("arg", model.args, this.args, workItem, context, outputExtensions);

        List<TaskInput.FileListInput> classpathParts = new ArrayList<>();
        for (int i = 0; i < model.classpath.size(); i++) {
            var part = model.classpath.get(i);
            classpathParts.add(TaskInput.files("classpath" + i, part, workItem, context, PathSensitivity.NONE));
        }
        this.classpath = new TaskInput.RecursiveFileListInput("classpath", classpathParts);

        if (model.mainClass != null) {
            this.mainClass = TaskInput.value("mainClass", model.mainClass, workItem);
        } else {
            this.mainClass = null;
        }

        this.inputs = new ArrayList<>();
        this.inputs.add(this.classpath);
        if (this.mainClass != null) {
            this.inputs.add(this.mainClass);
        }
        this.inputs.addAll(args.stream().flatMap(ArgumentProcessor.Arg::inputs).toList());

        this.classpathScopedJvm = model.classpathScopedJvm;
    }

    @Override
    public List<TaskInput> inputs() {
        return inputs;
    }

    @Override
    public Map<String, String> outputTypes() {
        return outputExtensions;
    }

    @Override
    protected void run(Context context) {
        boolean useJar = mainClass == null;
        var classpath = this.classpath.paths(context);
        if (useJar && classpath.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one classpath entry when no main class is provided");
        }

        var workingDirectory = context.taskWorkingDirectory(this);
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var logFile = workingDirectory.resolve("log.txt");

        var args = new ArrayList<String>();
        for (int i = 0; i < this.args.size(); i++) {
            var arg = this.args.get(i);
            args.addAll(arg.resolve(workingDirectory, name(), context, i));
        }

        try {
            if (useJar) {
                try (var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                    writer.append("-".repeat(80)).append("\n\n");
                    writer.append("Command-Line:\n");
                    writer.append(" - -jar\n - ").append(classpath.getFirst().toAbsolutePath().toString()).append("\n");
                    for (String s : args) {
                        writer.append(" - ").append(s).append("\n");
                    }
                    writer.append("-".repeat(80)).append("\n\n");
                }
                ToolDaemonExecutor.execute(classpath.getFirst(), logFile, args.toArray(String[]::new), context, classpathScopedJvm);
            } else {
                try (var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                    writer.append("-".repeat(80)).append("\n\n");
                    writer.append("Command-Line:\n");
                    writer.append(" - -cp\n - ").append(classpath.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator))).append("\n");
                    writer.append(" - ").append(mainClass.value().toString()).append("\n");
                    for (String s : args) {
                        writer.append(" - ").append(s).append("\n");
                    }
                    writer.append("-".repeat(80)).append("\n\n");
                }
                if (!(mainClass.value() instanceof Value.StringValue mainClassValue)) {
                    throw new IllegalArgumentException("mainClass must be a string");
                }
                ToolDaemonExecutor.execute(classpath, mainClassValue.value(), logFile, args.toArray(String[]::new), context, classpathScopedJvm);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
