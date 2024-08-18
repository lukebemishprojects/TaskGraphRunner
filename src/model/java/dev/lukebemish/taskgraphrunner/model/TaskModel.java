package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@JsonAdapter(TaskModel.Adapter.class)
public sealed abstract class TaskModel {
    protected final String name;

    protected TaskModel(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    static final class Adapter extends GsonAdapter<TaskModel> {
        private static final Map<String, TypeAdapter<? extends TaskModel>> TASK_TYPES = Map.of(
                "downloadManifest", new DownloadManifest.Specialized(),
                "downloadJson", new DownloadJson.Specialized(),
                "downloadDistribution", new DownloadDistribution.Specialized(),
                "downloadMappings", new DownloadMappings.Specialized(),
                "splitClassesResources", new SplitClassesResources.Specialized(),
                "listClasspath", new ListClasspath.Specialized(),
                "injectSources", new InjectSources.Specialized(),
                "patchSources", new PatchSources.Specialized(),
                "retrieveData", new RetrieveData.Specialized(),
                "tool", new Tool.Specialized()
        );
        private static final Map<Class<? extends TaskModel>, String> TASK_TYPE_NAMES = Map.of(
            DownloadManifest.class, "downloadManifest",
            DownloadJson.class, "downloadJson",
            DownloadDistribution.class, "downloadDistribution",
            DownloadMappings.class, "downloadMappings",
            SplitClassesResources.class, "splitClassesResources",
            ListClasspath.class, "listClasspath",
            InjectSources.class, "injectSources",
            PatchSources.class, "patchSources",
            RetrieveData.class, "retrieveData",
            Tool.class, "tool"
        );

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public void write(JsonWriter out, TaskModel value) throws IOException {
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
        public TaskModel read(JsonReader in) {
            JsonObject json = GSON.fromJson(in, JsonObject.class);
            var type = json.get("type").getAsString();
            var taskType = TASK_TYPES.get(type);
            if (taskType == null) {
                throw new IllegalArgumentException("Unknown task type `" + type + "`");
            }
            return taskType.fromJsonTree(json);
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class Tool extends TaskModel {
        public final List<Argument> args = new ArrayList<>();

        public Tool(String name, List<Argument> args) {
            super(name);
            this.args.addAll(args);
        }

        private static final class Specialized extends FieldAdapter<Tool> {
            @Override
            public Function<Values, Tool> build(Builder<Tool> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var arguments = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                return values -> new Tool(values.get(name), values.get(arguments));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadManifest extends TaskModel {
        public DownloadManifest(String name) {
            super(name);
        }

        private static final class Specialized extends FieldAdapter<DownloadManifest> {
            @Override
            public Function<Values, DownloadManifest> build(Builder<DownloadManifest> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                return values -> new DownloadManifest(values.get(name));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadJson extends TaskModel {
        public Input version;
        public Input manifest;

        public DownloadJson(String name, Input version, Input manifest) {
            super(name);
            this.version = version;
            this.manifest = manifest;
        }

        private static final class Specialized extends FieldAdapter<DownloadJson> {
            @Override
            public Function<Values, DownloadJson> build(Builder<DownloadJson> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var version = builder.field("version", task -> task.version, Input.class);
                var manifest = builder.field("manifest", task -> task.manifest, Input.class);
                return values -> new DownloadJson(values.get(name), values.get(version), values.get(manifest));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadDistribution extends TaskModel {
        public Input distribution;
        public Input versionJson;

        public DownloadDistribution(String name, Input distribution, Input versionJson) {
            super(name);
            this.distribution = distribution;
            this.versionJson = versionJson;
        }

        private static final class Specialized extends FieldAdapter<DownloadDistribution> {
            @Override
            public Function<Values, DownloadDistribution> build(Builder<DownloadDistribution> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var distribution = builder.field("distribution", task -> task.distribution, Input.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                return values -> new DownloadDistribution(values.get(name), values.get(distribution), values.get(versionJson));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadMappings extends TaskModel {
        public Input distribution;
        public Input versionJson;

        public DownloadMappings(String name, Input distribution, Input versionJson) {
            super(name);
            this.distribution = distribution;
            this.versionJson = versionJson;
        }

        private static final class Specialized extends FieldAdapter<DownloadMappings> {
            @Override
            public Function<Values, DownloadMappings> build(Builder<DownloadMappings> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var distribution = builder.field("distribution", task -> task.distribution, Input.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                return values -> new DownloadMappings(values.get(name), values.get(distribution), values.get(versionJson));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class SplitClassesResources extends TaskModel {
        public Input input;
        public @Nullable Input excludePattern;

        public SplitClassesResources(String name, Input input, @Nullable Input excludePattern) {
            super(name);
            this.input = input;
            this.excludePattern = excludePattern;
        }

        private static final class Specialized extends FieldAdapter<SplitClassesResources> {
            @Override
            public Function<Values, SplitClassesResources> build(Builder<SplitClassesResources> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var excludePattern = builder.field("excludePattern", task -> task.excludePattern, Input.class);
                return values -> new SplitClassesResources(values.get(name), values.get(input), values.get(excludePattern));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class ListClasspath extends TaskModel {
        public Input versionJson;
        public @Nullable Input additionalLibraries;

        public ListClasspath(String name, Input versionJson, @Nullable Input additionalLibraries) {
            super(name);
            this.versionJson = versionJson;
            this.additionalLibraries = additionalLibraries;
        }

        private static final class Specialized extends FieldAdapter<ListClasspath> {
            @Override
            public Function<Values, ListClasspath> build(Builder<ListClasspath> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                var additionalLibraries = builder.field("additionalLibraries", task -> task.additionalLibraries, Input.class);
                return values -> new ListClasspath(values.get(name), values.get(versionJson), values.get(additionalLibraries));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class InjectSources extends TaskModel {
        public Input input;
        public Input sources;

        public InjectSources(String name, Input input, Input sources) {
            super(name);
            this.input = input;
            this.sources = sources;
        }

        private static final class Specialized extends FieldAdapter<InjectSources> {
            @Override
            public Function<Values, InjectSources> build(Builder<InjectSources> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var sources = builder.field("sources", task -> task.sources, Input.class);
                return values -> new InjectSources(values.get(name), values.get(input), values.get(sources));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class PatchSources extends TaskModel {
        public Input input;
        public Input patches;

        public PatchSources(String name, Input input, Input patches) {
            super(name);
            this.input = input;
            this.patches = patches;
        }

        private static final class Specialized extends FieldAdapter<PatchSources> {
            @Override
            public Function<Values, PatchSources> build(Builder<PatchSources> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var patches = builder.field("patches", task -> task.patches, Input.class);
                return values -> new PatchSources(values.get(name), values.get(input), values.get(patches));
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class RetrieveData extends TaskModel {
        public Input input;
        public Input path;

        public RetrieveData(String name, Input input, Input path) {
            super(name);
            this.input = input;
            this.path = path;
        }

        private static final class Specialized extends FieldAdapter<RetrieveData> {
            @Override
            public Function<Values, RetrieveData> build(Builder<RetrieveData> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var path = builder.field("path", task -> task.path, Input.class);
                return values -> new RetrieveData(values.get(name), values.get(input), values.get(path));
            }
        }
    }
}
