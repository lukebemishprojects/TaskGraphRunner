package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

@JsonAdapter(Argument.ArgumentAdapter.class)
public abstract sealed class Argument {
    /**
     * A string pattern for the argument; any {@code "{}"} in it will be replaced by the argument value
     */
    public @Nullable String pattern;

    public Argument(@Nullable String pattern) {
        this.pattern = pattern;
    }

    public static Argument.ValueInput direct(String value) {
        return new Argument.ValueInput(null, new InputValue.DirectInput(new Value.StringValue(value)));
    }

    public abstract Stream<InputHandle> inputs();

    static final class ArgumentAdapter extends GsonAdapter<Argument> {
        private static final Map<String, TypeAdapter<? extends Argument>> TASK_TYPES = Map.of(
            "value", new ValueInput.Specialized(),
            "fileInput", new FileInput.Specialized(),
            "fileOutput", new FileOutput.Specialized(),
            "classpath", new Classpath.Specialized(),
            "zip", new Zip.Specialized(),
            "librariesFile", new LibrariesFile.Specialized()
        );
        private static final Map<Class<? extends Argument>, String> TASK_TYPE_NAMES = Map.of(
            ValueInput.class, "value",
            FileInput.class, "fileInput",
            FileOutput.class, "fileOutput",
            Classpath.class, "classpath",
            Zip.class, "zip",
            LibrariesFile.class, "librariesFile"
        );

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public void write(JsonWriter out, Argument value) throws IOException {
            if (value instanceof ValueInput valueInput && (!(valueInput.input instanceof InputValue.DirectInput directInput) || directInput.value() instanceof Value.StringValue)) {
                GSON.getAdapter(InputValue.class).write(out, valueInput.input);
                return;
            }
            out.beginObject();
            var type = TASK_TYPE_NAMES.get(value.getClass());
            out.name("type").value(type);
            TypeAdapter adapter = TASK_TYPES.get(type);
            for (var entry : adapter.toJsonTree(value).getAsJsonObject().entrySet()) {
                out.name(entry.getKey());
                GSON.toJson(entry.getValue(), out);
            }
            out.endObject();
        }

        @Override
        public Argument read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.STRING) {
                return new ValueInput(null, GSON.getAdapter(InputValue.class).read(in));
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

    @JsonAdapter(ArgumentAdapter.class)
    public static final class ValueInput extends Argument {
        public InputValue input;

        public ValueInput(@Nullable String pattern, InputValue input) {
            super(pattern);
            this.input = input;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.empty();
        }

        private static final class Specialized extends FieldAdapter<ValueInput> {
            @Override
            public Function<Values, ValueInput> build(Builder<ValueInput> builder) {
                var pattern = builder.field("pattern", arg -> arg.pattern, String.class);
                var input = builder.field("input", arg -> arg.input, InputValue.class);
                return values -> new ValueInput(values.get(pattern), values.get(input));
            }
        }
    }

    @JsonAdapter(ArgumentAdapter.class)
    public static final class FileInput extends Argument {
        public Input input;
        public PathSensitivity pathSensitivity;

        public FileInput(@Nullable String pattern, Input input, PathSensitivity pathSensitivity) {
            super(pattern);
            this.input = input;
            this.pathSensitivity = pathSensitivity;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> input, input -> this.input = input));
        }

        private static final class Specialized extends FieldAdapter<FileInput> {
            @Override
            public Function<Values, FileInput> build(Builder<FileInput> builder) {
                var pattern = builder.field("pattern", arg -> arg.pattern, String.class);
                var input = builder.field("input", arg -> arg.input, Input.class);
                var pathSensitivity = builder.field("pathSensitivity", arg -> arg.pathSensitivity, PathSensitivity.class);
                return values -> new FileInput(values.get(pattern), values.get(input), values.get(pathSensitivity));
            }
        }
    }

    @JsonAdapter(ArgumentAdapter.class)
    public static final class FileOutput extends Argument {
        public String name;
        public String extension;

        public FileOutput(@Nullable String pattern, String name, String extension) {
            super(pattern);
            this.name = name;
            this.extension = extension;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.empty();
        }

        private static final class Specialized extends FieldAdapter<FileOutput> {
            @Override
            public Function<Values, FileOutput> build(Builder<FileOutput> builder) {
                var pattern = builder.field("pattern", arg -> arg.pattern, String.class);
                var name = builder.field("name", arg -> arg.name, String.class);
                var extension = builder.field("extension", arg -> arg.extension, String.class);
                return values -> new FileOutput(values.get(pattern), values.get(name), values.get(extension));
            }
        }
    }

    @JsonAdapter(ArgumentAdapter.class)
    public static final class LibrariesFile extends Argument {
        public final List<Input> input = new ArrayList<>();
        public @Nullable InputValue prefix;

        public LibrariesFile(@Nullable String pattern, List<Input> input, @Nullable InputValue prefix) {
            super(pattern);
            this.input.addAll(input);
            this.prefix = prefix;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return InputHandle.mutableList(input);
        }

        private static final class Specialized extends FieldAdapter<LibrariesFile> {
            @Override
            public Function<Values, LibrariesFile> build(Builder<LibrariesFile> builder) {
                var pattern = builder.field("pattern", arg -> arg.pattern, String.class);
                var input = builder.field("input", arg -> arg.input, TypeToken.getParameterized(List.class, Input.class).getType());
                var prefix = builder.field("prefix", arg -> arg.prefix, InputValue.class);
                return values -> new LibrariesFile(values.get(pattern), values.get(input), values.get(prefix));
            }
        }
    }

    @JsonAdapter(ArgumentAdapter.class)
    public static final class Classpath extends Argument {
        public final List<Input> input = new ArrayList<>();

        public Classpath(@Nullable String pattern, List<Input> input) {
            super(pattern);
            this.input.addAll(input);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return InputHandle.mutableList(input);
        }

        private static final class Specialized extends FieldAdapter<Classpath> {
            @Override
            public Function<Values, Classpath> build(Builder<Classpath> builder) {
                var pattern = builder.field("pattern", arg -> arg.pattern, String.class);
                var input = builder.field("input", arg -> arg.input, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> new Classpath(values.get(pattern), values.get(input));
            }
        }
    }

    @JsonAdapter(ArgumentAdapter.class)
    public static final class Zip extends Argument {
        public final List<Input> inputs = new ArrayList<>();
        public PathSensitivity pathSensitivity;

        public Zip(@Nullable String pattern, List<Input> inputs, PathSensitivity pathSensitivity) {
            super(pattern);
            this.inputs.addAll(inputs);
            this.pathSensitivity = pathSensitivity;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return InputHandle.mutableList(inputs);
        }

        private static final class Specialized extends FieldAdapter<Zip> {
            @Override
            public Function<Values, Zip> build(Builder<Zip> builder) {
                var pattern = builder.field("pattern", arg -> arg.pattern, String.class);
                var inputs = builder.field("inputs", arg -> arg.inputs, TypeToken.getParameterized(List.class, Input.class).getType());
                var pathSensitivity = builder.field("pathSensitivity", arg -> arg.pathSensitivity, PathSensitivity.class);
                return values -> new Zip(values.get(pattern), values.get(inputs), values.get(pathSensitivity));
            }
        }
    }
}
