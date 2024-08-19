package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ArgumentProcessor {
    private ArgumentProcessor() {}

    static void processArgs(List<Argument> sourceArgs, List<Arg> args, WorkItem workItem, Context context, Map<String, String> outputExtensions) {
        for (int i = 0; i < sourceArgs.size(); i++) {
            var arg = sourceArgs.get(i);
            var name = "arg" + i;
            args.add(switch (arg) {
                case Argument.Classpath classpath -> new Arg.Classpath(TaskInput.files(name+"classpath", classpath.input, workItem, context, PathSensitivity.NONE), classpath.file, classpath.prefix);
                case Argument.FileInput fileInput -> new Arg.InputFile(TaskInput.file(name+"file", fileInput.input, workItem, context, fileInput.pathSensitivity));
                case Argument.ValueInput valueInput -> new Arg.Value(TaskInput.value(name+"value", valueInput.input, workItem));
                case Argument.Zip zip -> {
                    var inputs = new ArrayList<TaskInput.FileListInput>();
                    for (int j = 0; j < zip.inputs.size(); j++) {
                        var input = zip.inputs.get(j);
                        inputs.add(TaskInput.files(name+"zip"+j, input, workItem, context, zip.pathSensitivity));
                    }
                    yield new Arg.Zip(inputs, zip.prefix, zip.groupPrefix);
                }
                case Argument.FileOutput fileOutput -> {
                    var existing = outputExtensions.get(fileOutput.name);
                    if (existing != null && !existing.equals(fileOutput.extension)) {
                        throw new IllegalArgumentException("Output extension mismatch for " + fileOutput.name + ", requested both " + existing + " and " + fileOutput.extension);
                    }
                    outputExtensions.put(fileOutput.name, fileOutput.extension);
                    yield new Arg.OutputFile(fileOutput.name);
                }
            });
        }
    }

    sealed interface Arg {
        record Value(TaskInput.ValueInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(stringifyValue(input().value()));
            }

            private static String stringifyValue(dev.lukebemish.taskgraphrunner.model.Value value) {
                return switch (value) {
                    case dev.lukebemish.taskgraphrunner.model.Value.BooleanValue booleanValue -> booleanValue.value().toString();
                    case dev.lukebemish.taskgraphrunner.model.Value.ListValue listValue -> "["+listValue.value().stream().map(Value::stringifyValue).collect(Collectors.joining(","))+"]";
                    case dev.lukebemish.taskgraphrunner.model.Value.MapValue mapValue -> "["+mapValue.value().entrySet().stream().map(e ->
                        e.getKey()+":"+stringifyValue(e.getValue())
                    ).collect(Collectors.joining(","))+"]";
                    case dev.lukebemish.taskgraphrunner.model.Value.NumberValue numberValue -> numberValue.value().toString();
                    case dev.lukebemish.taskgraphrunner.model.Value.StringValue stringValue -> stringValue.value();
                };
            }
        }

        record InputFile(TaskInput.HasFileInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(input.path(context).toAbsolutePath().toString());
            }
        }

        record Classpath(TaskInput.FileListInput input, boolean file, @Nullable String prefix) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                if (file) {
                    Path filePath = workingDirectory.resolve("args." + argCount + ".txt");
                    var content = input.paths(context).stream().map(path -> {
                        if (prefix == null) {
                            return path.toAbsolutePath().toString();
                        }
                        return prefix + path.toAbsolutePath();
                    }).collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator();
                    try (var writer = Files.newBufferedWriter(filePath)) {
                        writer.write(content);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return List.of(filePath.toAbsolutePath().toString());
                } else {
                    var content = input.paths(context).stream().map(path -> path.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
                    if (prefix != null) {
                        content = prefix + content;
                    }
                    return List.of(content);
                }
            }
        }

        record Zip(List<TaskInput.FileListInput> input, String prefix, String groupPrefix) implements Arg {
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
                    if (groupPrefix != null) {
                        paths.add(groupPrefix);
                    }
                    for (var files : lists) {
                        var content = files.get(i).toAbsolutePath().toString();
                        if (prefix != null) {
                            content = prefix + content;
                        }
                        paths.add(content);
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
                return List.of(context.taskOutputPath(taskName, outputName).toAbsolutePath().toString());
            }
        }

        Stream<TaskInput> inputs();

        List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount);
    }
}
