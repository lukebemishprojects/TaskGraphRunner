package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.Value;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ArgumentProcessor {
    private ArgumentProcessor() {}

    static void processArgs(String prefix, List<Argument> sourceArgs, List<Arg> args, WorkItem workItem, Context context, Map<String, String> outputExtensions) {
        for (int i = 0; i < sourceArgs.size(); i++) {
            var arg = sourceArgs.get(i);
            var name = prefix + i;
            var pattern = arg.pattern == null ? "{}" : arg.pattern;
            args.add(switch (arg) {
                case Argument.Classpath classpath -> {
                    List<TaskInput.FileListInput> inputs = new ArrayList<>();
                    for (int j = 0; j < classpath.input.size(); j++) {
                        var input = classpath.input.get(j);
                        inputs.add(TaskInput.files(name+"classpath"+j, input, workItem, context, PathSensitivity.NONE));
                    }
                    yield new Arg.Classpath(name, pattern, inputs);
                }
                case Argument.FileInput fileInput -> new Arg.InputFile(name, pattern, TaskInput.file(name+"file", fileInput.input, workItem, context, fileInput.pathSensitivity));
                case Argument.ValueInput valueInput -> new Arg.Value(name, pattern, TaskInput.value(name+"value", valueInput.input, workItem));
                case Argument.Untracked untracked -> new Arg.Untracked(name, pattern, TaskInput.value(name+"untracked", untracked.value, workItem));
                case Argument.Zip zip -> {
                    var inputs = new ArrayList<TaskInput.FileListInput>();
                    for (int j = 0; j < zip.inputs.size(); j++) {
                        var input = zip.inputs.get(j);
                        inputs.add(TaskInput.files(name+"zip"+j, input, workItem, context, zip.pathSensitivity));
                    }
                    yield new Arg.Zip(name, pattern, inputs);
                }
                case Argument.FileOutput fileOutput -> {
                    var existing = outputExtensions.get(fileOutput.name);
                    if (existing != null && !existing.equals(fileOutput.extension)) {
                        throw new IllegalArgumentException("Output extension mismatch for " + fileOutput.name + ", requested both " + existing + " and " + fileOutput.extension);
                    }
                    outputExtensions.put(fileOutput.name, fileOutput.extension);
                    yield new Arg.OutputFile(name, pattern, fileOutput.name);
                }
                case Argument.LibrariesFile librariesFile -> {
                    TaskInput.ValueInput prefixInput = null;
                    if (librariesFile.prefix != null) {
                        prefixInput = TaskInput.value(name+"librariesFilePrefix", librariesFile.prefix, workItem, new Value.DirectStringValue(""));
                    }
                    List<TaskInput.FileListInput> inputs = new ArrayList<>();
                    for (int j = 0; j < librariesFile.input.size(); j++) {
                        var input = librariesFile.input.get(j);
                        inputs.add(TaskInput.files(name+"librariesFile"+j, input, workItem, context, PathSensitivity.NONE));
                    }
                    yield new Arg.LibrariesFile(name, pattern, inputs, prefixInput);
                }
            });
        }
    }

    sealed interface Arg {
        record Untracked(String name, String pattern, TaskInput.ValueInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of();
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return Value.stringifyValue(input().value()).stream().map(v -> pattern.replace("{}", v)).toList();
            }
        }

        record Value(String name, String pattern, TaskInput.ValueInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(TaskInput.direct(name, pattern), input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return stringifyValue(input().value()).stream().map(v -> pattern.replace("{}", v)).toList();
            }

            private static List<String> stringifyValue(dev.lukebemish.taskgraphrunner.model.Value value) {
                return switch (value) {
                    case dev.lukebemish.taskgraphrunner.model.Value.BooleanValue booleanValue -> List.of(booleanValue.value().toString());
                    case dev.lukebemish.taskgraphrunner.model.Value.ListValue listValue -> listValue.value().stream()
                        .flatMap(v -> stringifyValue(v).stream()).toList();
                    case dev.lukebemish.taskgraphrunner.model.Value.MapValue mapValue -> mapValue.value().entrySet().stream()
                        .flatMap(e -> stringifyValue(e.getValue()).stream().map(v -> e.getKey()+"="+v)).toList();
                    case dev.lukebemish.taskgraphrunner.model.Value.NumberValue numberValue -> List.of(numberValue.value().toString());
                    case dev.lukebemish.taskgraphrunner.model.Value.DirectStringValue stringValue -> List.of(stringValue.value());
                    case dev.lukebemish.taskgraphrunner.model.Value.SystemPropertyValue systemPropertyValue -> List.of(systemPropertyValue.value());
                };
            }
        }

        record InputFile(String name, String pattern, TaskInput.HasFileInput input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(TaskInput.direct(name, pattern), input);
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(pattern.replace("{}", input.path(context).toAbsolutePath().toString()));
            }
        }

        record LibrariesFile(String name, String pattern, List<TaskInput.FileListInput> input, TaskInput.@Nullable ValueInput prefix) implements Arg {

            @Override
            public Stream<TaskInput> inputs() {
                if (prefix == null) {
                    return Stream.concat(Stream.of(TaskInput.direct(name, pattern)), input.stream());
                }
                return Stream.concat(Stream.of(TaskInput.direct(name, pattern), prefix), input.stream());
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                Path filePath = workingDirectory.resolve("args." + argCount + ".txt");
                var content = input.stream().flatMap(i -> i.paths(context).stream()).map(path -> {
                    if (prefix == null) {
                        return path.toAbsolutePath().toString();
                    }
                    return ((String) prefix.value().value()) + path.toAbsolutePath();
                }).collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator();
                try (var writer = Files.newBufferedWriter(filePath)) {
                    writer.write(content);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return List.of(pattern.replace("{}", filePath.toAbsolutePath().toString()));
            }
        }

        record Classpath(String name, String pattern, List<TaskInput.FileListInput> input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.concat(Stream.of(TaskInput.direct(name, pattern)), input.stream());
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(pattern.replace("{}", input.stream().map(i -> i.classpath(context)).collect(Collectors.joining(File.pathSeparator))));
            }
        }

        record Zip(String name, String pattern, List<TaskInput.FileListInput> input) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.concat(Stream.of(TaskInput.direct(name, pattern)), input.stream());
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
                        paths.add(pattern.replace("{}", files.get(i).toAbsolutePath().toString()));
                    }
                }

                return paths;
            }
        }

        record OutputFile(String name, String pattern, String outputName) implements Arg {
            @Override
            public Stream<TaskInput> inputs() {
                return Stream.of(TaskInput.direct(name, pattern));
            }

            @Override
            public List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount) {
                return List.of(pattern.replace("{}", context.taskOutputPath(context.getTask(taskName), outputName).toAbsolutePath().toString()));
            }
        }

        Stream<TaskInput> inputs();

        List<String> resolve(Path workingDirectory, String taskName, Context context, int argCount);
    }
}
