package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@JsonAdapter(Argument.Adapter.class)
public sealed interface Argument {
    final class Adapter extends GsonAdapter<Argument> {
        private static final Map<String, TypeAdapter<? extends Argument>> TASK_TYPES = Map.of(
            "value", new ValueInput.Specialized(),
            "fileInput", new FileInput.Specialized(),
            "fileOutput", new FileOutput.Specialized(),
            "classpath", new Classpath.Specialized(),
            "zip", new Zip.Specialized()
        );
        private static final Map<Class<? extends Argument>, String> TASK_TYPE_NAMES = Map.of(
            ValueInput.class, "value",
            FileInput.class, "fileInput",
            FileOutput.class, "fileOutput",
            Classpath.class, "classpath",
            Zip.class, "zip"
        );

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public void write(JsonWriter out, Argument value) throws IOException {
            out.beginObject();
            out.name("type").value(TASK_TYPE_NAMES.get(value.getClass()));
            TypeAdapter adapter = GSON.getAdapter(value.getClass());
            for (var entry : adapter.toJsonTree(value).getAsJsonObject().entrySet()) {
                out.name(entry.getKey());
                GSON.toJson(entry.getValue(), out);
            }
            out.endObject();
        }

        @Override
        public Argument read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.STRING) {
                return new ValueInput(GSON.getAdapter(Input.class).read(in));
            }

            JsonObject json = GSON.fromJson(in, JsonObject.class);
            var type = json.get("type").getAsString();
            var taskType = TASK_TYPES.get(type);
            if (taskType == null) {
                throw new IllegalArgumentException("Unknown argument type `" + type + "`");
            }
            return taskType.fromJsonTree(json);
        }
    }

    @JsonAdapter(Adapter.class)
    final class ValueInput implements Argument {
        public Input input;

        public ValueInput(Input input) {
            this.input = input;
        }

        private static final class Specialized extends FieldAdapter<ValueInput> {
            @Override
            public Function<Values, ValueInput> build(Builder<ValueInput> builder) {
                var input = builder.field("input", arg -> arg.input, Input.class);
                return values -> new ValueInput(values.get(input));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class FileInput implements Argument {
        public Input input;
        public PathSensitivity pathSensitivity;

        public FileInput(Input input, PathSensitivity pathSensitivity) {
            this.input = input;
            this.pathSensitivity = pathSensitivity;
        }

        private static final class Specialized extends FieldAdapter<FileInput> {
            @Override
            public Function<Values, FileInput> build(Builder<FileInput> builder) {
                var input = builder.field("input", arg -> arg.input, Input.class);
                var pathSensitivity = builder.field("pathSensitivity", arg -> arg.pathSensitivity, PathSensitivity.class);
                return values -> new FileInput(values.get(input), values.get(pathSensitivity));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class FileOutput implements Argument {
        public String name;
        public String extension;

        public FileOutput(String name, String extension) {
            this.name = name;
            this.extension = extension;
        }

        private static final class Specialized extends FieldAdapter<FileOutput> {
            @Override
            public Function<Values, FileOutput> build(Builder<FileOutput> builder) {
                var name = builder.field("name", arg -> arg.name, String.class);
                var extension = builder.field("extension", arg -> arg.extension, String.class);
                return values -> new FileOutput(values.get(name), values.get(extension));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class Classpath implements Argument {
        public Input input;
        public boolean file;

        public Classpath(Input input, boolean file) {
            this.input = input;
            this.file = file;
        }

        private static final class Specialized extends FieldAdapter<Classpath> {
            @Override
            public Function<Values, Classpath> build(Builder<Classpath> builder) {
                var input = builder.field("input", arg -> arg.input, Input.class);
                var file = builder.field("file", arg -> arg.file, Boolean.class);
                return values -> new Classpath(values.get(input), values.get(file));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class Zip implements Argument {
        public final List<Input> inputs = new ArrayList<>();
        public PathSensitivity pathSensitivity;

        public Zip(List<Input> inputs, PathSensitivity pathSensitivity) {
            this.inputs.addAll(inputs);
            this.pathSensitivity = pathSensitivity;
        }

        private static final class Specialized extends FieldAdapter<Zip> {
            @Override
            public Function<Values, Zip> build(Builder<Zip> builder) {
                var inputs = builder.field("inputs", arg -> arg.inputs, TypeToken.getParameterized(List.class, Input.class).getType());
                var pathSensitivity = builder.field("pathSensitivity", arg -> arg.pathSensitivity, PathSensitivity.class);
                return values -> new Zip(values.get(inputs), values.get(pathSensitivity));
            }
        }
    }
}
