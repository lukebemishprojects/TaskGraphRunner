package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ToolTask extends Task {
    private final List<Arg> args;
    private final List<TaskInput> inputs;
    private final Map<String, String> outputExtensions;

    public ToolTask(TaskModel.Tool model, WorkItem workItem, Context context) {
        super(model.name());

        this.outputExtensions = new HashMap<>();

        this.args = new ArrayList<>();
        processArgs(model.args(), this.args, workItem, context, outputExtensions);

        this.inputs = args.stream().flatMap(Arg::inputs).toList();
    }

    private static void processArgs(List<Argument> sourceArgs, List<Arg> args, WorkItem workItem, Context context, Map<String, String> outputExtensions) {
        for (int i = 0; i < sourceArgs.size(); i++) {
            var arg = sourceArgs.get(i);
            var name = "arg" + i;
            args.add(switch (arg) {
                case Argument.Classpath classpath -> new Arg.Classpath(TaskInput.files(name+"classpath", classpath.input(), workItem, context, PathSensitivity.NONE), classpath.file());
                case Argument.FileInput fileInput -> new Arg.InputFile(TaskInput.file(name+"file", fileInput.input(), workItem, context, fileInput.pathSensitivity()));
                case Argument.Value value -> new Arg.Value(TaskInput.value(name+"value", value.input(), workItem));
                case Argument.Zip zip -> {
                    var inputs = new ArrayList<TaskInput.FileListInput>();
                    for (int j = 0; j < zip.inputs().size(); j++) {
                        var input = zip.inputs().get(j);
                        inputs.add(TaskInput.files(name+"zip"+j, input, workItem, context, zip.pathSensitivity()));
                    }
                    yield new Arg.Zip(inputs);
                }
                case Argument.FileOutput fileOutput -> {
                    var existing = outputExtensions.get(fileOutput.name());
                    if (existing != null && !existing.equals(fileOutput.extension())) {
                        throw new IllegalArgumentException("Output extension mismatch for " + fileOutput.name() + ", requested both " + existing + " and " + fileOutput.extension());
                    }
                    outputExtensions.put(fileOutput.name(), fileOutput.extension());
                    yield new Arg.OutputFile(fileOutput.name());
                }
            });
        }
    }

    @Override
    public List<TaskInput> inputs() {
        return this.inputs;
    }

    @Override
    public Map<String, String> outputTypes() {
        return outputExtensions;
    }

    @Override
    protected void run(Context context) {
        var javaExecutablePath = ProcessHandle.current()
            .info()
            .command()
            .orElseThrow();

        var command = new ArrayList<String>();
        command.add(javaExecutablePath);

        var stateName = context.taskStatePath(name()).getFileName().toString();
        var taskName = stateName.substring(0, stateName.lastIndexOf('.'));
        var workingDirectory = context.taskDirectory(name()).resolve(taskName);
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            command.addAll(arg.resolve(workingDirectory, name(), context, i));
        }

        var logFile = workingDirectory.resolve("log.txt");

        try {
            try (var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                writer.append("-".repeat(80)).append("\n\n");
                writer.append("Command-Line:\n");
                for (String s : command) {
                    writer.append(" - ").append(s).append("\n");
                }
                writer.append("-".repeat(80)).append("\n\n");
            }

            var process = new ProcessBuilder()
                .directory(workingDirectory.toFile())
                .command(command)
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

    private sealed interface Arg {
        record Value(TaskInput.ValueInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(input.value().toString());
            }
        }

        record InputFile(TaskInput.HasFileInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(shortPath(workingDirectory, input.path(context)));
            }
        }

        record Classpath(TaskInput.FileListInput input, boolean file) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                if (file) {
                    Path filePath = workingDirectory.resolve("args." + argCount + ".txt");
                    var content = input.paths(context).stream().map(path -> shortPath(workingDirectory, path)).collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator();
                    try (var writer = Files.newBufferedWriter(filePath)) {
                        writer.write(content);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return List.of(shortPath(workingDirectory, filePath));
                } else {
                    return List.of(input.paths(context).stream().map(path -> shortPath(workingDirectory, path)).collect(Collectors.joining(File.pathSeparator)));
                }
            }
        }

        record Zip(List<TaskInput.FileListInput> input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return input.stream().map(Function.identity());
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                int length = Integer.MAX_VALUE;

                List<String> paths = new ArrayList<>();

                List<List<Path>> lists = new ArrayList<>();

                for (var files : input) {
                    var found = files.paths(context);
                    lists.add(found);
                    length = Math.min(length, found.size());
                }

                for (int i = 0; i < length; i++) {
                    for (var files : lists) {
                        paths.add(shortPath(workingDirectory, files.get(i)));
                    }
                }

                return paths;
            }
        }

        record OutputFile(String outputName) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.empty();
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(shortPath(workingDirectory, context.taskOutputPath(taskName, outputName)));
            }
        }

        private static String shortPath(Path workingDirectory, Path path) {
            var relative = workingDirectory.relativize(path).toString();
            var absolute = path.toAbsolutePath().toString();
            return absolute.length() > relative.length() ? relative : absolute;
        }

        Stream<TaskInput> inputs();

        List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount);
    }
}
