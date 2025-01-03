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
import java.util.stream.Stream;

@JsonAdapter(TaskModel.Adapter.class)
public sealed abstract class TaskModel {
    protected final String name;
    public @Nullable String parallelism;

    protected TaskModel(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public final String type() {
        return Adapter.TASK_TYPE_NAMES.get(getClass());
    }

    public abstract Stream<InputHandle> inputs();

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
            taskTypes.put("daemonExecutedTool", new DaemonExecutedTool.Specialized());
            taskTypeNames.put(DaemonExecutedTool.class, "daemonExecutedTool");
            taskTypes.put("tool", new Tool.Specialized());
            taskTypeNames.put(Tool.class, "tool");
            taskTypes.put("compile", new Compile.Specialized());
            taskTypeNames.put(Compile.class, "compile");
            taskTypes.put("interfaceInjection", new InterfaceInjection.Specialized());
            taskTypeNames.put(InterfaceInjection.class, "interfaceInjection");
            taskTypes.put("jst", new Jst.Specialized());
            taskTypeNames.put(Jst.class, "jst");
            taskTypes.put("downloadAssets", new DownloadAssets.Specialized());
            taskTypeNames.put(DownloadAssets.class, "downloadAssets");
            taskTypes.put("transformMappings", new TransformMappings.Specialized());
            taskTypeNames.put(TransformMappings.class, "transformMappings");

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
    public static final class TransformMappings extends TaskModel {
        public MappingsFormat format;
        public MappingsSource source;
        public @Nullable Input sourceJar = null;

        public TransformMappings(String name, MappingsFormat format, MappingsSource source) {
            super(name);
            this.format = format;
            this.source = source;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.concat(
                source.inputs(),
                Stream.of(InputHandle.of(() -> sourceJar, i -> this.sourceJar = i))
            );
        }

        private static final class Specialized extends FieldAdapter<TransformMappings> {
            @Override
            public Function<Values, TransformMappings> build(Builder<TransformMappings> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var format = builder.field("format", task -> task.format, MappingsFormat.class);
                var source = builder.field("source", task -> task.source, MappingsSource.class);
                var sourceJar = builder.field("sourceJar", task -> task.sourceJar, Input.class);
                return values -> {
                    var task = new TransformMappings(values.get(name), values.get(format), values.get(source));
                    task.parallelism = values.get(parallelism);
                    task.sourceJar = values.get(sourceJar);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class InterfaceInjection extends TaskModel {
        public Input input;
        public Input interfaceInjection;
        public final List<Input> classpath = new ArrayList<>();

        public InterfaceInjection(String name, Input input, Input interfaceInjection, List<Input> classpath) {
            super(name);
            this.input = input;
            this.interfaceInjection = interfaceInjection;
            this.classpath.addAll(classpath);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.concat(Stream.of(
                InputHandle.of(() -> input, i -> this.input = i),
                InputHandle.of(() -> this.interfaceInjection, i -> this.interfaceInjection = i)
            ), InputHandle.mutableList(classpath));
        }

        private static final class Specialized extends FieldAdapter<InterfaceInjection> {
            @Override
            public Function<Values, InterfaceInjection> build(Builder<InterfaceInjection> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var interfaceInjection = builder.field("interfaceInjection", task -> task.interfaceInjection, Input.class);
                var classpath = builder.field("classpath", task -> task.classpath, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> {
                    var task = new InterfaceInjection(values.get(name), values.get(input), values.get(interfaceInjection), values.get(classpath));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class Jst extends TaskModel {
        public final List<Argument> args = new ArrayList<>();
        public Input input;
        public final List<Input> classpath = new ArrayList<>();
        public final List<Input> executionClasspath = new ArrayList<>();
        public @Nullable Input accessTransformers = null;
        public @Nullable Input interfaceInjection = null;
        public @Nullable MappingsSource parchmentData = null;
        public @Nullable Input binaryInput = null;
        public boolean classpathScopedJvm = false;

        public Jst(String name, List<Argument> args, Input input, List<Input> classpath, @Nullable List<Input> executionClasspath) {
            super(name);
            this.args.addAll(args);
            this.input = input;
            this.classpath.addAll(classpath);
            if (executionClasspath != null) {
                this.executionClasspath.addAll(executionClasspath);
            }
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(
                Stream.of(
                    InputHandle.of(() -> input, i -> this.input = i),
                    InputHandle.of(() -> binaryInput, i -> this.binaryInput = i),
                    InputHandle.of(() -> accessTransformers, i -> this.accessTransformers = i),
                    InputHandle.of(() -> interfaceInjection, i -> this.interfaceInjection = i)
                ).filter(h -> h.getInput() != null),
                (parchmentData != null) ? parchmentData.inputs() : Stream.<InputHandle>empty(),
                InputHandle.mutableList(classpath),
                InputHandle.mutableList(executionClasspath),
                args.stream().flatMap(Argument::inputs)
            ).flatMap(Function.identity());
        }

        private static final class Specialized extends FieldAdapter<Jst> {
            @Override
            public Function<Values, Jst> build(Builder<Jst> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var args = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                var input = builder.field("input", task -> task.input, Input.class);
                var classpath = builder.field("classpath", task -> task.classpath, TypeToken.getParameterized(List.class, Input.class).getType());
                var jstClasspath = builder.field("executionClasspath", task -> task.executionClasspath, TypeToken.getParameterized(List.class, Input.class).getType());
                var accessTransformers = builder.field("accessTransformers", task -> task.accessTransformers, Input.class);
                var interfaceInjection = builder.field("interfaceInjection", task -> task.interfaceInjection, Input.class);
                var parchmentData = builder.field("parchmentData", task -> task.parchmentData, MappingsSource.class);
                var classpathScopedJvm = builder.field("classpathScopedJvm", task -> task.classpathScopedJvm, Boolean.class);
                var binaryInput = builder.field("binaryInput", task -> task.binaryInput, Input.class);
                return values -> {
                    var jst = new Jst(values.get(name), values.get(args), values.get(input), values.get(classpath), values.get(jstClasspath));
                    jst.accessTransformers = values.get(accessTransformers);
                    jst.interfaceInjection = values.get(interfaceInjection);
                    jst.parchmentData = values.get(parchmentData);
                    jst.parallelism = values.get(parallelism);
                    jst.classpathScopedJvm = values.get(classpathScopedJvm) == Boolean.TRUE;
                    jst.binaryInput = values.get(binaryInput);
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

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(
                Stream.of(InputHandle.of(() -> sources, i -> this.sources = i)),
                InputHandle.mutableList(sourcepath),
                InputHandle.mutableList(classpath),
                args.stream().flatMap(Argument::inputs)
            ).flatMap(Function.identity());
        }

        private static final class Specialized extends FieldAdapter<Compile> {
            @Override
            public Function<Values, Compile> build(Builder<Compile> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var arguments = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                var sources = builder.field("sources", task -> task.sources, Input.class);
                var sourcepath = builder.field("sourcepath", task -> task.sourcepath, TypeToken.getParameterized(List.class, Input.class).getType());
                var classpath = builder.field("classpath", task -> task.classpath, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> {
                    var task = new Compile(values.get(name), values.get(arguments), values.get(sources), values.get(sourcepath), values.get(classpath));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DaemonExecutedTool extends TaskModel {
        public final List<Argument> args = new ArrayList<>();
        public final List<Input> classpath = new ArrayList<>();
        public @Nullable InputValue mainClass;
        public boolean classpathScopedJvm = false;

        public DaemonExecutedTool(String name, List<Argument> args, List<Input> classpath, @Nullable InputValue mainClass) {
            super(name);
            this.args.addAll(args);
            this.classpath.addAll(classpath);
            this.mainClass = mainClass;
        }

        public DaemonExecutedTool(String name, List<Argument> args, Input jar) {
            super(name);
            this.args.addAll(args);
            this.classpath.add(new Input.ListInput(List.of(jar)));
            this.mainClass = null;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.concat(args.stream().flatMap(Argument::inputs), InputHandle.mutableList(classpath));
        }

        private static final class Specialized extends FieldAdapter<DaemonExecutedTool> {
            @Override
            public Function<Values, DaemonExecutedTool> build(Builder<DaemonExecutedTool> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var args = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                var classpath = builder.field("classpath", task -> task.classpath, TypeToken.getParameterized(List.class, Input.class).getType());
                var mainClass = builder.field("mainClass", task -> task.mainClass, InputValue.class);
                var classpathScopedJvm = builder.field("classpathScopedJvm", task -> task.classpathScopedJvm, Boolean.class);
                return values -> {
                    var task = new DaemonExecutedTool(values.get(name), values.get(args), values.get(classpath), values.get(mainClass));
                    task.parallelism = values.get(parallelism);
                    task.classpathScopedJvm = values.get(classpathScopedJvm) == Boolean.TRUE;
                    return task;
                };
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

        @Override
        public Stream<InputHandle> inputs() {
            return args.stream().flatMap(Argument::inputs);
        }

        private static final class Specialized extends FieldAdapter<Tool> {
            @Override
            public Function<Values, Tool> build(Builder<Tool> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var arguments = builder.field("args", task -> task.args, TypeToken.getParameterized(List.class, Argument.class).getType());
                return values -> {
                    var task = new Tool(values.get(name), values.get(arguments));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadAssets extends TaskModel {
        public Input versionJson;

        public DownloadAssets(String name, Input versionJson) {
            super(name);
            this.versionJson = versionJson;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> versionJson, i -> this.versionJson = i));
        }

        private static final class Specialized extends FieldAdapter<DownloadAssets> {
            @Override
            public Function<Values, DownloadAssets> build(Builder<DownloadAssets> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                return values -> {
                    var task = new DownloadAssets(values.get(name), values.get(versionJson));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadManifest extends TaskModel {
        public DownloadManifest(String name) {
            super(name);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.empty();
        }

        private static final class Specialized extends FieldAdapter<DownloadManifest> {
            @Override
            public Function<Values, DownloadManifest> build(Builder<DownloadManifest> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                return values -> {
                    var task = new DownloadManifest(values.get(name));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadJson extends TaskModel {
        public InputValue version;
        public Input manifest;

        public DownloadJson(String name, InputValue version, Input manifest) {
            super(name);
            this.version = version;
            this.manifest = manifest;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> manifest, i -> this.manifest = i));
        }

        private static final class Specialized extends FieldAdapter<DownloadJson> {
            @Override
            public Function<Values, DownloadJson> build(Builder<DownloadJson> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var version = builder.field("version", task -> task.version, InputValue.class);
                var manifest = builder.field("manifest", task -> task.manifest, Input.class);
                return values -> {
                    var task = new DownloadJson(values.get(name), values.get(version), values.get(manifest));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadDistribution extends TaskModel {
        public InputValue distribution;
        public Input versionJson;

        public DownloadDistribution(String name, InputValue distribution, Input versionJson) {
            super(name);
            this.distribution = distribution;
            this.versionJson = versionJson;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> versionJson, i -> this.versionJson = i));
        }

        private static final class Specialized extends FieldAdapter<DownloadDistribution> {
            @Override
            public Function<Values, DownloadDistribution> build(Builder<DownloadDistribution> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var distribution = builder.field("distribution", task -> task.distribution, InputValue.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                return values -> {
                    var task = new DownloadDistribution(values.get(name), values.get(distribution), values.get(versionJson));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class DownloadMappings extends TaskModel {
        public InputValue distribution;
        public Input versionJson;

        public DownloadMappings(String name, InputValue distribution, Input versionJson) {
            super(name);
            this.distribution = distribution;
            this.versionJson = versionJson;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> versionJson, i -> this.versionJson = i));
        }

        private static final class Specialized extends FieldAdapter<DownloadMappings> {
            @Override
            public Function<Values, DownloadMappings> build(Builder<DownloadMappings> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var distribution = builder.field("distribution", task -> task.distribution, InputValue.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                return values -> {
                    var task = new DownloadMappings(values.get(name), values.get(distribution), values.get(versionJson));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class SplitClassesResources extends TaskModel {
        public Input input;
        public @Nullable InputValue excludePattern;

        public SplitClassesResources(String name, Input input, @Nullable InputValue excludePattern) {
            super(name);
            this.input = input;
            this.excludePattern = excludePattern;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> input, i -> this.input = i));
        }

        private static final class Specialized extends FieldAdapter<SplitClassesResources> {
            @Override
            public Function<Values, SplitClassesResources> build(Builder<SplitClassesResources> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var excludePattern = builder.field("excludePattern", task -> task.excludePattern, InputValue.class);
                return values -> {
                    var task = new SplitClassesResources(values.get(name), values.get(input), values.get(excludePattern));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class ListClasspath extends TaskModel {
        public Input versionJson;
        public @Nullable InputValue additionalLibraries;

        public ListClasspath(String name, Input versionJson, @Nullable InputValue additionalLibraries) {
            super(name);
            this.versionJson = versionJson;
            this.additionalLibraries = additionalLibraries;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> versionJson, i -> this.versionJson = i));
        }

        private static final class Specialized extends FieldAdapter<ListClasspath> {
            @Override
            public Function<Values, ListClasspath> build(Builder<ListClasspath> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var versionJson = builder.field("versionJson", task -> task.versionJson, Input.class);
                var additionalLibraries = builder.field("additionalLibraries", task -> task.additionalLibraries, InputValue.class);
                return values -> {
                    var task = new ListClasspath(values.get(name), values.get(versionJson), values.get(additionalLibraries));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class InjectSources extends TaskModel {
        public final List<Input> inputs = new ArrayList<>();

        public InjectSources(String name, List<Input> inputs) {
            super(name);
            this.inputs.addAll(inputs);
        }

        @Override
        public Stream<InputHandle> inputs() {
            return InputHandle.mutableList(inputs);
        }

        private static final class Specialized extends FieldAdapter<InjectSources> {
            @Override
            public Function<Values, InjectSources> build(Builder<InjectSources> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var inputs = builder.field("inputs", task -> task.inputs, TypeToken.getParameterized(List.class, Input.class).getType());
                return values -> {
                    var task = new InjectSources(values.get(name), values.get(inputs));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
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

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(
                InputHandle.of(() -> input, i -> this.input = i),
                InputHandle.of(() -> patches, i -> this.patches = i)
            );
        }

        private static final class Specialized extends FieldAdapter<PatchSources> {
            @Override
            public Function<Values, PatchSources> build(Builder<PatchSources> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var patches = builder.field("patches", task -> task.patches, Input.class);
                return values -> {
                    var task = new PatchSources(values.get(name), values.get(input), values.get(patches));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }

    @JsonAdapter(Adapter.class)
    public static final class RetrieveData extends TaskModel {
        public Input input;
        public InputValue path;

        public RetrieveData(String name, Input input, InputValue path) {
            super(name);
            this.input = input;
            this.path = path;
        }

        @Override
        public Stream<InputHandle> inputs() {
            return Stream.of(InputHandle.of(() -> input, i -> this.input = i));
        }

        private static final class Specialized extends FieldAdapter<RetrieveData> {
            @Override
            public Function<Values, RetrieveData> build(Builder<RetrieveData> builder) {
                var name = builder.field("name", task -> task.name, String.class);
                var parallelism = builder.field("parallelism", task -> task.parallelism, String.class);
                var input = builder.field("input", task -> task.input, Input.class);
                var path = builder.field("path", task -> task.path, InputValue.class);
                return values -> {
                    var task = new RetrieveData(values.get(name), values.get(input), values.get(path));
                    task.parallelism = values.get(parallelism);
                    return task;
                };
            }
        }
    }
}
