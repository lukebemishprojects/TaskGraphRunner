package dev.lukebemish.taskgraphrunner.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.Input;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.util.HashUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public sealed interface TaskInput extends RecordedInput {
    String name();

    default List<String> dependencies() {
        return List.of();
    }

    sealed interface HasFileInput extends TaskInput {
        Path path(Context context);
    }

    sealed interface FileListInput extends TaskInput {
        List<Path> paths(Context context);

        default String classpath(Context context) {
            return paths(context).stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        }
    }

    record ValueInput(String name, Value value) implements TaskInput {

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            switch (value) {
                case Value.BooleanValue bool -> digest.update(bool.value() ? (byte) 1 : (byte) 0);
                case Value.NumberValue numberValue -> {
                    switch (numberValue.value()) {
                        case Double d -> {
                            ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES);
                            buffer.putDouble(d);
                            digest.update(buffer);
                        }
                        case Float f -> {
                            ByteBuffer buffer = ByteBuffer.allocate(Float.BYTES);
                            buffer.putFloat(f);
                            digest.update(buffer);
                        }
                        case Long l -> {
                            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                            buffer.putLong(l);
                            digest.update(buffer);
                        }
                        case Integer i -> {
                            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
                            buffer.putInt(i);
                            digest.update(buffer);
                        }
                        case Short s -> {
                            ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES);
                            buffer.putShort(s);
                            digest.update(buffer);
                        }
                        case Byte b -> digest.update(b);
                        default -> throw new IllegalArgumentException("Unsupported value type: "+value.getClass());
                    }
                }
                case Value.StringValue stringValue -> digest.update(stringValue.value().getBytes(StandardCharsets.UTF_8));
                case Value.MapValue mapValue -> {
                    for (var entry : mapValue.value().entrySet()) {
                        digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                        digest.update(entry.getValue().toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                case Value.ListValue listValue -> {
                    for (var element : listValue.value()) {
                        digest.update(element.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported value type: "+value.getClass());
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            return JsonUtils.GSON.getAdapter(Value.class).toJsonTree(value);
        }
    }

    record FileInput(String name, Path path, PathSensitivity pathSensitivity) implements HasFileInput {

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            switch (pathSensitivity) {
                case ABSOLUTE -> digest.update(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
                case NONE -> {}
                case NAME_ONLY -> digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            HasFileInput.super.hashContents(digest, context);
            try {
                HashUtils.hash(path, digest);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonObject object = new JsonObject();
            object.addProperty("type", "file");
            switch (pathSensitivity) {
                case ABSOLUTE -> object.addProperty("path", path.toAbsolutePath().toString());
                case NONE -> {}
                case NAME_ONLY -> object.addProperty("path", path.getFileName().toString());
            }
            object.addProperty("sensitivity", pathSensitivity.name());
            return object;
        }

        @Override
        public Path path(Context context) {
            return path();
        }
    }

    record TaskOutputInput(String name, TaskOutput output) implements HasFileInput {

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            digest.update(output.taskName().getBytes(StandardCharsets.UTF_8));
            digest.update(output.name().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            try {
                HashUtils.hash(output.getPath(context), digest);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public List<String> dependencies() {
            return List.of(output.taskName());
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonObject object = new JsonObject();
            object.addProperty("type", "taskOutput");
            object.addProperty("task", output.taskName());
            object.addProperty("output", output.name());
            return object;
        }

        @Override
        public Path path(Context context) {
            return context.taskOutputPath(output.taskName(), output.name());
        }
    }

    record LibraryListFileListInput(String name, HasFileInput libraryFile) implements FileListInput {
        @Override
        public List<String> dependencies() {
            return libraryFile.dependencies();
        }

        @Override
        public List<Path> paths(Context context) {
            try (var reader = Files.newBufferedReader(libraryFile.path(context))) {
                return reader.lines().map(line -> pathNotation(context, line)).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            libraryFile.hashReference(digest, context);
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            var paths = paths(context);
            for (Path path : paths) {
                try {
                    HashUtils.hash(path, digest);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonObject object = new JsonObject();
            object.addProperty("type", "libraryList");
            object.add("file", libraryFile.recordedValue(context));
            return object;
        }
    }

    private static Path pathNotation(Context context, String line) {
        return context.artifactManifest().resolve(line);
    }

    record RecursiveFileListInput(String name, List<FileListInput> inputs) implements FileListInput {

        @Override
        public List<Path> paths(Context context) {
            return inputs.stream().flatMap(i -> i.paths(context).stream()).toList();
        }

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            buffer.putInt(inputs.size());
            digest.update(buffer);
            for (FileListInput input : inputs) {
                input.hashReference(digest, context);
            }
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            buffer.putInt(inputs.size());
            digest.update(buffer);
            for (FileListInput input : inputs) {
                input.hashContents(digest, context);
            }
        }

        @Override
        public List<String> dependencies() {
            return inputs.stream().flatMap(input -> input.dependencies().stream()).toList();
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonArray array = new JsonArray();
            for (FileListInput input : inputs) {
                array.add(input.recordedValue(context));
            }
            return array;
        }
    }

    record SimpleFileListInput(String name, List<HasFileInput> inputs) implements FileListInput {
        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            buffer.putInt(inputs.size());
            digest.update(buffer);
            for (HasFileInput input : inputs) {
                input.hashReference(digest, context);
            }
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            buffer.putInt(inputs.size());
            digest.update(buffer);
            for (HasFileInput input : inputs) {
                input.hashContents(digest, context);
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonArray array = new JsonArray();
            for (HasFileInput input : inputs) {
                array.add(input.recordedValue(context));
            }
            return array;
        }

        @Override
        public List<Path> paths(Context context) {
            return inputs.stream().map(input -> input.path(context)).toList();
        }

        @Override
        public List<String> dependencies() {
            return inputs.stream().flatMap(input -> input.dependencies().stream()).toList();
        }
    }

    static ValueInput value(String name, Input modelInput, WorkItem workItem) {
        return value(name, modelInput, workItem, null);
    }

    static ValueInput value(String name, Input modelInput, WorkItem workItem, Value defaultValue) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                var value = workItem.parameters.get(parameterInput.parameter());
                if (value == null) {
                    if (defaultValue == null) {
                        throw new IllegalArgumentException("No such parameter `"+ parameterInput.parameter()+"`");
                    }
                    yield new ValueInput(name, defaultValue);
                }
                yield new ValueInput(name, value);
            }
            case Input.TaskInput ignored -> throw new IllegalArgumentException("Cannot convert task input to value");
            case Input.DirectInput directInput -> new ValueInput(name, directInput.value());
            case Input.ListInput listInput -> {
                List<Value> values = new ArrayList<>();
                for (int i = 0; i < listInput.inputs().size(); i++) {
                    values.add(value(name+"_"+i, listInput.inputs().get(i), workItem, null).value());
                }
                yield new ValueInput(name, new Value.ListValue(values));
            }
        };
    }

    static HasFileInput file(String name, Input modelInput, WorkItem workItem, Context context, PathSensitivity pathSensitivity) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                var value = workItem.parameters.get(parameterInput.parameter());
                if (!(value instanceof Value.StringValue stringValue)) {
                    throw new IllegalArgumentException("Parameter `"+ parameterInput.parameter()+"` is not a string");
                }
                yield new FileInput(name, pathNotation(context, stringValue.value()), pathSensitivity);
            }
            case Input.TaskInput taskInput -> {
                if (pathSensitivity != PathSensitivity.NONE) {
                    throw new IllegalArgumentException("Cannot use path sensitivity with task input");
                }
                yield new TaskOutputInput(name, new TaskOutput(taskInput.output().taskName(), taskInput.output().name()));
            }
            case Input.DirectInput directInput -> {
                if (!(directInput.value() instanceof Value.StringValue stringValue)) {
                    throw new IllegalArgumentException("Value is not a string");
                }
                yield new FileInput(name, pathNotation(context, stringValue.value()), pathSensitivity);
            }
            case Input.ListInput ignored -> throw new IllegalArgumentException("Cannot create a single file from a list of inputs");
        };
    }

    static FileListInput files(String name, Input modelInput, WorkItem workItem, Context context, PathSensitivity pathSensitivity) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                Value value = workItem.parameters.get(parameterInput.parameter());
                yield fileListFromValue(name, context, pathSensitivity, parameterInput, value);
            }
            case Input.TaskInput taskInput -> {
                if (pathSensitivity != PathSensitivity.NONE) {
                    throw new IllegalArgumentException("Cannot use path sensitivity with task input");
                }
                yield new LibraryListFileListInput(name, new TaskOutputInput(name, new TaskOutput(taskInput.output().taskName(), taskInput.output().name())));
            }
            case Input.DirectInput directInput -> fileListFromValue(name, context, pathSensitivity, null, directInput.value());
            case Input.ListInput listInput -> {
                List<HasFileInput> fileInputs = new ArrayList<>();
                for (int i = 0; i < listInput.inputs().size(); i++) {
                    fileInputs.add(file(name + "_" + i, listInput.inputs().get(i), workItem, context, pathSensitivity));
                }
                yield new SimpleFileListInput(name, fileInputs);
            }
        };
    }

    private static FileListInput fileListFromValue(String name, Context context, PathSensitivity pathSensitivity, Input.@Nullable ParameterInput parameterInput, Value value) {
        if (value instanceof Value.StringValue stringValue) {
            return new LibraryListFileListInput(name, new FileInput(name, pathNotation(context, stringValue.value()), pathSensitivity));
        } else if (value instanceof Value.ListValue listValue) {
            List<HasFileInput> inputs = new ArrayList<>(listValue.value().size());
            for (int i = 0; i < listValue.value().size(); i++) {
                var singleValue = listValue.value().get(i);
                if (singleValue instanceof Value.StringValue stringValue) {
                    inputs.add(new FileInput(name +"_"+i, pathNotation(context, stringValue.value()), pathSensitivity));
                } else {
                    if (parameterInput == null) {
                        throw new IllegalArgumentException("Array value contains non-string value at index "+i);
                    }
                    throw new IllegalArgumentException("Array parameter `"+ parameterInput.parameter()+"` contains non-string value at index "+i);
                }
            }
            return new SimpleFileListInput(name, inputs);
        } else if (value != null) {
            if (parameterInput == null) {
                throw new IllegalArgumentException("Value is not a string or list of strings");
            }
            throw new IllegalArgumentException("Parameter `"+ parameterInput.parameter()+"` is not a string or list of strings");
        } else {
            if (parameterInput == null) {
                throw new IllegalArgumentException("No value provided");
            }
            throw new IllegalArgumentException("No such parameter `"+ parameterInput.parameter()+"`");
        }
    }
}
