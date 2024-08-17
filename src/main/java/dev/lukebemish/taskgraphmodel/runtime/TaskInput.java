package dev.lukebemish.taskgraphmodel.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.lukebemish.taskgraphmodel.model.Input;
import dev.lukebemish.taskgraphmodel.model.WorkItem;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    record ValueInput(String name, Object value) implements TaskInput {

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            switch (value) {
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
                case Byte b -> {
                    digest.update(b);
                }
                case Boolean bool -> {
                    digest.update(bool ? (byte) 1 : (byte) 0);
                }
                case Number number -> {
                    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
                    buffer.putInt(Objects.hashCode(number));
                    digest.update(buffer);
                }
                case String string -> digest.update(string.getBytes(StandardCharsets.UTF_8));
                case Character character -> {
                    ByteBuffer buffer = ByteBuffer.allocate(Character.BYTES);
                    buffer.putChar(character);
                    digest.update(buffer);
                }
                default -> throw new IllegalArgumentException("Unsupported value type: "+value.getClass());
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            return switch (value) {
                case Boolean bool -> new JsonPrimitive(bool);
                case Number number -> new JsonPrimitive(number);
                case String string -> new JsonPrimitive(string);
                case Character character -> new JsonPrimitive(character);
                default -> throw new IllegalArgumentException("Unsupported value type: "+value.getClass());
            };
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
            try (var is = Files.newInputStream(path)) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonObject object = new JsonObject();
            object.addProperty("type", "file");
            object.addProperty("path", path.toString());
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
            var task = context.getTask(output.taskName());
            task.hashReference(digest, context);
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            try (var is = Files.newInputStream(output.getPath(context))) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
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

    record ValueListInput(String name, List<ValueInput> inputs) implements TaskInput {

        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            for (ValueInput input : inputs) {
                input.hashReference(digest, context);
            }
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            for (ValueInput input : inputs) {
                input.hashContents(digest, context);
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonArray array = new JsonArray();
            for (ValueInput input : inputs) {
                array.add(input.recordedValue(context));
            }
            return array;
        }

        List<Object> values() {
            return inputs.stream().map(input -> input.value).toList();
        }
    }

    record LibraryListFileListInput(String name, HasFileInput libraryFile) implements FileListInput {

        @Override
        public List<Path> paths(Context context) {
            try (var reader = Files.newBufferedReader(libraryFile.path(context))) {
                return reader.lines().map(line -> {
                    if (line.startsWith("file:")) {
                        return Path.of(line.substring("file:".length()));
                    } else if (line.startsWith("artifact:")) {
                        var notation = line.substring("artifact:".length());
                        return context.findArtifact(notation);
                    } else {
                        throw new IllegalArgumentException("Unknown library line: "+line);
                    }
                }).collect(Collectors.toList());
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
                try (var is = Files.newInputStream(path)) {
                    byte[] buffer = new byte[2048];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
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

    record SimpleFileListInput(String name, List<FileInput> inputs) implements FileListInput {
        @Override
        public void hashReference(ByteConsumer digest, Context context) {
            for (FileInput input : inputs) {
                input.hashReference(digest, context);
            }
        }

        @Override
        public void hashContents(ByteConsumer digest, Context context) {
            for (FileInput input : inputs) {
                input.hashContents(digest, context);
            }
        }

        @Override
        public JsonElement recordedValue(Context context) {
            JsonArray array = new JsonArray();
            for (FileInput input : inputs) {
                array.add(input.recordedValue(context));
            }
            return array;
        }

        @Override
        public List<Path> paths(Context context) {
            return inputs.stream().map(input -> input.path(context)).toList();
        }
    }

    static ValueInput value(String name, Input modelInput, WorkItem workItem) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                JsonElement json = workItem.parameters().get(parameterInput.parameter());
                if (json == null || !json.isJsonPrimitive()) {
                    throw new IllegalArgumentException("No such primitive parameter `"+parameterInput.parameter()+"`");
                }
                yield new ValueInput(name, json.getAsJsonPrimitive());
            }
            case Input.TaskInput taskInput -> throw new IllegalArgumentException("Cannot convert task input to value");
        };
    }

    static ValueListInput values(String name, Input modelInput, WorkItem workItem) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                JsonElement json = workItem.parameters().get(parameterInput.parameter());
                if (json == null || !json.isJsonArray()) {
                    throw new IllegalArgumentException("No such array parameter `"+parameterInput.parameter()+"`");
                }
                JsonArray array = json.getAsJsonArray();
                List<ValueInput> inputs = new ArrayList<>(array.size());
                for (int i = 0; i < array.size(); i++) {
                    var value = array.get(i);
                    if (!value.isJsonPrimitive()) {
                        throw new IllegalArgumentException("Array parameter `"+parameterInput.parameter()+"` contains non-primitive value at index "+i);
                    }
                    inputs.add(new ValueInput(name+"_"+i, value));
                }
                yield new ValueListInput(name, inputs);
            }
            case Input.TaskInput taskInput -> throw new IllegalArgumentException("Cannot convert task input to value");
        };
    }

    static HasFileInput file(String name, Input modelInput, WorkItem workItem, PathSensitivity pathSensitivity) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                JsonElement json = workItem.parameters().get(parameterInput.parameter());
                if (json == null || !json.isJsonPrimitive()) {
                    throw new IllegalArgumentException("No such primitive parameter `"+parameterInput.parameter()+"`");
                }
                if (!json.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Parameter `"+parameterInput.parameter()+"` is not a string");
                }
                yield new FileInput(name, Path.of(json.getAsJsonPrimitive().getAsString()), pathSensitivity);
            }
            case Input.TaskInput taskInput -> {
                if (pathSensitivity != PathSensitivity.NONE) {
                    throw new IllegalArgumentException("Cannot use path sensitivity with task input");
                }
                yield new TaskOutputInput(name, new TaskOutput(taskInput.task(), taskInput.output()));
            }
        };
    }

    static FileListInput files(String name, Input modelInput, WorkItem workItem, PathSensitivity pathSensitivity) {
        return switch (modelInput) {
            case Input.ParameterInput parameterInput -> {
                JsonElement json = workItem.parameters().get(parameterInput.parameter());
                if (json == null || !json.isJsonArray()) {
                    throw new IllegalArgumentException("No such array parameter `"+parameterInput.parameter()+"`");
                }
                List<FileInput> inputs = new ArrayList<>(json.getAsJsonArray().size());
                for (int i = 0; i < json.getAsJsonArray().size(); i++) {
                    var value = json.getAsJsonArray().get(i);
                    if (!value.isJsonPrimitive()) {
                        throw new IllegalArgumentException("Array parameter `"+parameterInput.parameter()+"` contains non-primitive value at index "+i);
                    }
                    if (!value.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("Array parameter `"+parameterInput.parameter()+"` contains non-string value at index "+i);
                    }
                    inputs.add(new FileInput(name+"_"+i, Path.of(value.getAsJsonPrimitive().getAsString()), pathSensitivity));
                }
                yield new SimpleFileListInput(name, inputs);
            }
            case Input.TaskInput taskInput -> {
                if (pathSensitivity != PathSensitivity.NONE) {
                    throw new IllegalArgumentException("Cannot use path sensitivity with task input");
                }
                yield new LibraryListFileListInput(name, new TaskOutputInput(name, new TaskOutput(taskInput.task(), taskInput.output())));
            }
        };
    }
}
