package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

@JsonAdapter(MappingsSource.Adapter.class)
public sealed interface MappingsSource {
    final class Adapter extends GsonAdapter<MappingsSource> {
        private static final Map<String, TypeAdapter<? extends MappingsSource>> SOURCE_TYPES;
        private static final Map<Class<? extends MappingsSource>, String> SOURCE_TYPE_NAMES;

        static {
            var taskTypes = new HashMap<String, TypeAdapter<? extends MappingsSource>>();
            var taskTypeNames = new HashMap<Class<? extends MappingsSource>, String>();

            taskTypes.put("file", new File.Specialized());
            taskTypeNames.put(File.class, "file");
            taskTypes.put("reversed", new Reversed.Specialized());
            taskTypeNames.put(Reversed.class, "reversed");
            taskTypes.put("merged", new Merged.Specialized());
            taskTypeNames.put(Merged.class, "merged");
            taskTypes.put("mergedFiles", new MergedFiles.Specialized());
            taskTypeNames.put(MergedFiles.class, "mergedFiles");
            taskTypes.put("chained", new Chained.Specialized());
            taskTypeNames.put(Chained.class, "chained");
            taskTypes.put("chainedFiles", new ChainedFiles.Specialized());
            taskTypeNames.put(ChainedFiles.class, "chainedFiles");

            SOURCE_TYPES = Map.copyOf(taskTypes);
            SOURCE_TYPE_NAMES = Map.copyOf(taskTypeNames);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public void write(JsonWriter out, MappingsSource value) throws IOException {
            out.beginObject();
            var type = SOURCE_TYPE_NAMES.get(value.getClass());
            out.name("type").value(type);
            TypeAdapter adapter = SOURCE_TYPES.get(type);
            for (var entry : adapter.toJsonTree(value).getAsJsonObject().entrySet()) {
                out.name(entry.getKey());
                GSON.toJson(entry.getValue(), out);
            }
            out.endObject();
        }

        @Override
        public MappingsSource read(JsonReader in) {
            JsonObject json = GSON.fromJson(in, JsonObject.class);
            var type = json.get("type").getAsString();
            var sourceType = SOURCE_TYPES.get(type);
            if (sourceType == null) {
                throw new IllegalArgumentException("Unknown mappings source type `" + type + "`");
            }
            return sourceType.fromJsonTree(json);
        }
    }

    Stream<InputHandle> inputs();

    @JsonAdapter(Adapter.class)
    final class File implements MappingsSource {
        public Input input;

        public File(Input input) {
            this.input = input;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> input, value -> input = value));
        }

        private static final class Specialized extends FieldAdapter<File> {
            @Override
            public Function<Values, File> build(Builder<File> builder) {
                var input = builder.field("input", source -> source.input, Input.class);
                return values -> new File(values.get(input));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class Reversed implements MappingsSource {
        public MappingsSource source;

        public Reversed(MappingsSource source) {
            this.source = source;
        }

        private static final class Specialized extends FieldAdapter<Reversed> {
            @Override
            public Function<Values, Reversed> build(Builder<Reversed> builder) {
                var sourceKey = builder.field("source", source -> source.source, MappingsSource.class);
                return values -> new Reversed(values.get(sourceKey));
            }
        }

        @Override
        public Stream<InputHandle> inputs() {
            return source.inputs();
        }
    }

    @JsonAdapter(Adapter.class)
    final class Merged implements MappingsSource {
        public final List<MappingsSource> sources = new ArrayList<>();

        public Merged(List<MappingsSource> sources) {
            this.sources.addAll(sources);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return sources.stream().flatMap(MappingsSource::inputs);
        }

        private static final class Specialized extends FieldAdapter<Merged> {
            @Override
            public Function<Values, Merged> build(Builder<Merged> builder) {
                var sources = builder.field("sources", source -> source.sources, TypeToken.getParameterized(List.class, MappingsSource.class).getType());
                return values -> new Merged(values.get(sources));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class MergedFiles implements MappingsSource {
        public final List<Input> files = new ArrayList<>();

        public MergedFiles(List<Input> files) {
            this.files.addAll(files);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return InputHandle.mutableList(files);
        }

        private static final class Specialized extends FieldAdapter<MergedFiles> {
            @Override
            public Function<Values, MergedFiles> build(Builder<MergedFiles> builder) {
                var files = builder.field("files", source -> source.files, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> new MergedFiles(values.get(files));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class Chained implements MappingsSource {
        public final List<MappingsSource> sources = new ArrayList<>();

        public Chained(List<MappingsSource> sources) {
            this.sources.addAll(sources);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return sources.stream().flatMap(MappingsSource::inputs);
        }

        private static final class Specialized extends FieldAdapter<Chained> {
            @Override
            public Function<Values, Chained> build(Builder<Chained> builder) {
                var sources = builder.field("sources", source -> source.sources, TypeToken.getParameterized(List.class, MappingsSource.class).getType());
                return values -> new Chained(values.get(sources));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    final class ChainedFiles implements MappingsSource {
        public final List<Input> files = new ArrayList<>();

        public ChainedFiles(List<Input> files) {
            this.files.addAll(files);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return InputHandle.mutableList(files);
        }

        private static final class Specialized extends FieldAdapter<ChainedFiles> {
            @Override
            public Function<Values, ChainedFiles> build(Builder<ChainedFiles> builder) {
                var sources = builder.field("files", source -> source.files, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> new ChainedFiles(values.get(sources));
            }
        }
    }
}
