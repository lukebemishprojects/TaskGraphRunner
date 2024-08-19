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
import java.util.HashMap;
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
        private static final Map<String, TypeAdapter<? extends TaskModel>> TASK_TYPES;
        private static final Map<Class<? extends TaskModel>, String> TASK_TYPE_NAMES;

        static {
            var taskTypes = new HashMap<String, TypeAdapter<? extends TaskModel>>();
            var taskTypeNames = new HashMap<Class<? extends TaskModel>, String>();

            taskTypes.put("downloadManifest", new DownloadManifest.Specialized());
            taskTypeNames.put(DownloadManifest.class, "downloadManifest");
            taskTypes.put("downloadJson", new DownloadJson.Specialized());
            taskTypeNames.put(DownloadJson.class, "downloadJson");
            taskTypes.put("downloadDistribution", new DownloadDistribution.Specialized());
            taskTypeNames.put(DownloadDistribution.class, "downloadDistribution");
            taskTypes.put("downloadMappings", new DownloadMappings.Specialized());
            taskTypeNames.put(DownloadMappings.class, "downloadMappings");
            taskTypes.put("splitClassesResources", new SplitClassesResources.Specialized());
            taskTypeNames.put(SplitClassesResources.class, "splitClassesResources");
            taskTypes.put("listClasspath", new ListClasspath.Specialized());
            taskTypeNames.put(ListClasspath.class, "listClasspath");
            taskTypes.put("injectSources", new InjectSources.Specialized());
            taskTypeNames.put(InjectSources.class, "injectSources");
            taskTypes.put("patchSources", new PatchSources.Specialized());
            taskTypeNames.put(PatchSources.class, "patchSources");
            taskTypes.put("retrieveData", new RetrieveData.Specialized());
            taskTypeNames.put(RetrieveData.class, "retrieveData");
            taskTypes.put("tool", new Tool.Specialized());
            taskTypeNames.put(Tool.class, "tool");
            taskTypes.put("compile", new Compile.Specialized());
            taskTypeNames.put(Compile.class, "compile");
            taskTypes.put("jst", new Jst.Specialized());
            taskTypeNames.put(Jst.class, "jst");

            TASK_TYPES = Map.copyOf(taskTypes);
            TASK_TYPE_NAMES = Map.copyOf(taskTypeNames);
        }

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
    public static final class Jst extends TaskModel {
        public final List<Argument> jvmArgs = new ArrayList<>();
        public final List<Argument> args = new ArrayList<>();
        public Input input;
        public final List<Input> classpath = new ArrayList<>();
        public final List<Input> jstClasspath = new ArrayList<>();
        public @Nullable Input accessTransformers = null;
        public @Nullable Input interfaceInjection = null;
        public @Nullable Input parchmentData = null;

        public Jst(String name, List<Argument> jvmArgs, List<Argument> args, Input input, List<Input> classpath, @Nullable List<Input> jstClasspath) {
            super(name);
            this.args.addAll(args);
            this.jvmArgs.addAll(jvmArgs);
            this.input = input;
            this.classpath.addAll(classpath);
            if (jstClasspath != null) {
                this.jstClasspath.addAll(jstClasspath);
            }
        }

        private static final class Specialized extends FieldAdapter<Jst> {
            @Override
            public Function<Values, Jst> build(Builder<Jst> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var args = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                var jvmArgs = builder.field("jvmArgs", task -> task.jvmArgs, TypeToken.getParameterized(List.class, Argument.class).getType());
                var input = builder.field("input", task -> task.input, Input.class);
                var classpath = builder.field("classpath", task -> task.classpath, TypeToken.getParameterized(List.class, Input.class).getType());
                var jstClasspath = builder.field("jstClasspath", task -> task.jstClasspath, TypeToken.getParameterized(List.class, Input.class).getType());
                var accessTransformers = builder.field("accessTransformers", task -> task.accessTransformers, Input.class);
                var interfaceInjection = builder.field("interfaceInjection", task -> task.interfaceInjection, Input.class);
                var parchmentData = builder.field("parchmentData", task -> task.parchmentData, Input.class);
                return values -> {
                    var jst = new Jst(values.get(name), values.get(jvmArgs), values.get(args), values.get(input), values.get(classpath), values.get(jstClasspath));
                    jst.accessTransformers = values.get(accessTransformers);
                    jst.interfaceInjection = values.get(interfaceInjection);
                    jst.parchmentData = values.get(parchmentData);
                    return jst;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class Compile extends TaskModel {
        public final List<Argument> args = new ArrayList<>();
        public Input sources;
        public final List<Input> sourcepath = new ArrayList<>();
        public final List<Input> classpath = new ArrayList<>();

        public Compile(String name, List<Argument> args, Input sources, List<Input> sourcepath, List<Input> classpath) {
            super(name);
            this.args.addAll(args);
            this.sources = sources;
            this.sourcepath.addAll(sourcepath);
            this.classpath.addAll(classpath);
        }

        private static final class Specialized extends FieldAdapter<Compile> {
            @Override
            public Function<Values, Compile> build(Builder<Compile> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var arguments = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                var sources = builder.field("sources", task -> task.sources, Input.class);
                var sourcepath = builder.field("sourcepath", task -> task.sourcepath, TypeToken.getParameterized(List.class, Input.class).getType());
                var classpath = builder.field("classpath", task -> task.classpath, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> new Compile(values.get(name), values.get(arguments), values.get(sources), values.get(sourcepath), values.get(classpath));
            }
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
