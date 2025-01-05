package dev.lukebemish.taskgraphrunner.model.conversion;

import com.google.gson.Gson;
import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Distribution;
import dev.lukebemish.taskgraphrunner.model.Input;
import dev.lukebemish.taskgraphrunner.model.InputValue;
import dev.lukebemish.taskgraphrunner.model.MappingsSource;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NeoFormGenerator {
    private NeoFormGenerator() {}

    public static final class Options {
        private final @Nullable String accessTransformersParameter;
        private final @Nullable String injectedInterfacesParameter;
        private final @Nullable String parchmentDataParameter;
        private final boolean recompile;
        private final boolean fixLineNumbers;
        private final Distribution distribution;
        private final @Nullable Input versionJson;
        private final @Nullable Input clientJar;
        private final @Nullable Input serverJar;
        private final @Nullable Input clientMappings;
        private final @Nullable Input serverMappings;
        private final List<AdditionalJst> additionalJst = new ArrayList<>();

        private Options(@Nullable String accessTransformersParameter, @Nullable String injectedInterfacesParameter, @Nullable String parchmentDataParameter, boolean recompile, boolean fixLineNumbers, Distribution distribution, @Nullable Input versionJson, @Nullable Input clientJar, @Nullable Input serverJar, @Nullable Input clientMappings, @Nullable Input serverMappings) {
            this.accessTransformersParameter = accessTransformersParameter;
            this.injectedInterfacesParameter = injectedInterfacesParameter;
            this.parchmentDataParameter = parchmentDataParameter;
            this.recompile = recompile;
            this.fixLineNumbers = fixLineNumbers;
            this.distribution = distribution;
            this.versionJson = versionJson;
            this.clientJar = clientJar;
            this.serverJar = serverJar;
            this.clientMappings = clientMappings;
            this.serverMappings = serverMappings;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String accessTransformersParameter = null;
            private String injectedInterfacesParameter = null;
            private String parchmentDataParameter = null;
            private boolean recompile = true;
            private boolean fixLineNumbers = false;
            private Distribution distribution = Distribution.JOINED;
            private @Nullable Input versionJson = null;
            private @Nullable Input clientJar = null;
            private @Nullable Input serverJar = null;
            private @Nullable Input clientMappings = null;
            private @Nullable Input serverMappings = null;
            private final List<AdditionalJst> additionalJst = new ArrayList<>();

            private Builder() {}

            public Builder additionalJst(AdditionalJst jst) {
                this.additionalJst.add(jst);
                return this;
            }

            public Builder versionJson(Input versionJson) {
                this.versionJson = versionJson;
                return this;
            }

            public Builder clientJar(Input clientJar) {
                this.clientJar = clientJar;
                return this;
            }

            public Builder serverJar(Input serverJar) {
                this.serverJar = serverJar;
                return this;
            }

            public Builder clientMappings(Input clientMappings) {
                this.clientMappings = clientMappings;
                return this;
            }

            public Builder serverMappings(Input serverMappings) {
                this.serverMappings = serverMappings;
                return this;
            }

            public Builder accessTransformersParameter(String accessTransformersParameter) {
                this.accessTransformersParameter = accessTransformersParameter;
                return this;
            }

            public Builder interfaceInjectionDataParameter(String injectedInterfacesParameter) {
                this.injectedInterfacesParameter = injectedInterfacesParameter;
                return this;
            }

            public Builder parchmentDataParameter(String parchmentDataParameter) {
                this.parchmentDataParameter = parchmentDataParameter;
                return this;
            }

            public Builder recompile(boolean recompile) {
                this.recompile = recompile;
                return this;
            }

            public Builder fixLineNumbers(boolean fixLineNumbers) {
                this.fixLineNumbers = fixLineNumbers;
                return this;
            }

            public Builder distribution(Distribution distribution) {
                this.distribution = distribution;
                return this;
            }

            public Options build() {
                var options = new Options(accessTransformersParameter, injectedInterfacesParameter, parchmentDataParameter, recompile, fixLineNumbers, distribution, versionJson, clientJar, serverJar, clientMappings, serverMappings);
                options.additionalJst.addAll(additionalJst);
                return options;
            }
        }
    }

    /**
     * Convert a neoform config zip into a task graph config.
     * @param neoFormConfig the path to the neoform config zip
     * @param selfReference how the config zip should be referenced in the generated config
     * @return a new task graph config
     */
    public static Config convert(Path neoFormConfig, Value selfReference, Options options) throws IOException {
        NeoFormConfig source = null;
        try (var is = Files.newInputStream(neoFormConfig);
             var zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("config.json")) {
                    source = new Gson().fromJson(new InputStreamReader(zis, StandardCharsets.UTF_8), NeoFormConfig.class);
                    break;
                }
            }
        }

        String distribution = options.distribution.name().toLowerCase(Locale.ROOT);

        if (source == null) {
            throw new IllegalArgumentException("No config.json found in provided neoform config zip");
        }
        var config = new Config();
        config.parameters.put("neoFormZip", selfReference);
        config.parameters.put("additionalLibraries", new Value.ListValue(
            source.libraries().getOrDefault(distribution, List.of())
                .stream().map(s -> (Value) Value.artifact(s)).toList()
        ));

        for (var entry : source.data().entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            String path;
            if (value.isJsonPrimitive()) {
                path = value.getAsString();
            } else if (value.isJsonObject()) {
                path = value.getAsJsonObject().get(distribution).getAsString();
            } else {
                throw new IllegalArgumentException("Invalid value type for data entry" + key);
            }
            config.tasks.add(new TaskModel.RetrieveData(
                "generated_retrieve_" + key,
                new Input.ParameterInput("neoFormZip"),
                new InputValue.DirectInput(new Value.DirectStringValue(path))
            ));
        }

        Input downloadManifestInput = new Input.TaskInput(new Output("downloadManifest", "output"));
        Input downloadJsonInput = options.versionJson == null ? new Input.TaskInput(new Output("downloadJson", "output")) : options.versionJson;
        var listLibrariesName = "listLibraries";
        var stripTasks = new ArrayList<String>();

        for (var step : source.steps().getOrDefault(distribution, List.of())) {
            switch (step.type()) {
                case "downloadManifest" -> downloadManifestInput = new Input.TaskInput(new Output(step.name(), "output"));
                case "listLibraries" -> listLibrariesName = step.name();
                case "strip" -> stripTasks.add(step.name());
                default -> {}
            }
        }

        Output patches = null;
        Output vineflowerOutput = null;

        Map<String, Input> downloadInputs = new HashMap<>();

        for (var step : source.steps().getOrDefault(distribution, List.of())) {
            switch (step.type()) {
                case "downloadManifest" -> {
                    config.tasks.add(new TaskModel.DownloadManifest(step.name()));
                }
                case "downloadJson" -> {
                    if (options.versionJson != null) {
                        downloadInputs.put(step.name(), options.versionJson);
                    } else {
                        var task = new TaskModel.DownloadJson(
                            step.name(),
                            new InputValue.DirectInput(new Value.DirectStringValue(source.version())),
                            downloadManifestInput
                        );
                        downloadInputs.put(step.name(), new Input.TaskInput(new Output(step.name(), "output")));
                        config.tasks.add(task);
                    }
                }
                case "downloadClient" -> {
                    if (options.clientJar != null) {
                        downloadInputs.put(step.name(), options.clientJar);
                    } else {
                        var task = new TaskModel.DownloadDistribution(
                            step.name(),
                            new InputValue.DirectInput(new Value.DirectStringValue("client")),
                            downloadJsonInput
                        );
                        downloadInputs.put(step.name(), new Input.TaskInput(new Output(step.name(), "output")));
                        config.tasks.add(task);
                    }
                }
                case "downloadServer" -> {
                    if (options.serverJar != null) {
                        downloadInputs.put(step.name(), options.serverJar);
                    } else {
                        var task = new TaskModel.DownloadDistribution(
                            step.name(),
                            new InputValue.DirectInput(new Value.DirectStringValue("server")),
                            downloadJsonInput
                        );
                        downloadInputs.put(step.name(), new Input.TaskInput(new Output(step.name(), "output")));
                        config.tasks.add(task);
                    }
                }
                case "downloadClientMappings" -> {
                    if (options.clientMappings != null) {
                        downloadInputs.put(step.name(), options.clientMappings);
                    } else {
                        var task = new TaskModel.DownloadMappings(
                            step.name(),
                            new InputValue.DirectInput(new Value.DirectStringValue("client")),
                            downloadJsonInput
                        );
                        downloadInputs.put(step.name(), new Input.TaskInput(new Output(step.name(), "output")));
                        config.tasks.add(task);
                    }
                }
                case "downloadServerMappings" -> {
                    if (options.serverMappings != null) {
                        downloadInputs.put(step.name(), options.serverMappings);
                    } else {
                        var task = new TaskModel.DownloadMappings(
                            step.name(),
                            new InputValue.DirectInput(new Value.DirectStringValue("server")),
                            downloadJsonInput
                        );
                        downloadInputs.put(step.name(), new Input.TaskInput(new Output(step.name(), "output")));
                        config.tasks.add(task);
                    }
                }
                default -> {}
            }
        }

        for (var step : source.steps().getOrDefault(distribution, List.of())) {
            var task = switch (step.type()) {
                case "downloadManifest", "downloadJson", "downloadClient", "downloadServer", "downloadClientMappings", "downloadServerMappings" -> null;
                case "strip" -> new TaskModel.SplitClassesResources(
                    step.name(),
                    parseStepInput(downloadInputs, step, "input"),
                    null
                );
                case "listLibraries" -> new TaskModel.ListClasspath(
                    step.name(),
                    downloadJsonInput,
                    new InputValue.ParameterInput("additionalLibraries")
                );
                case "inject" -> new TaskModel.InjectSources(
                    step.name(),
                    List.of(
                        parseStepInput(downloadInputs, step, "input"),
                        new Input.TaskInput(new Output("generated_retrieve_inject", "output"))
                    )
                );
                case "patch" -> {
                    patches = new Output("generated_retrieve_patches", "output");
                    yield new TaskModel.PatchSources(
                        step.name(),
                        parseStepInput(downloadInputs, step, "input"),
                        new Input.TaskInput(new Output("generated_retrieve_patches", "output"))
                    );
                }
                default -> {
                    var function = source.functions().get(step.type());
                    if (function == null) {
                        throw new IllegalStateException("Unknown neoform step type: " + step.type());
                    }
                    List<Argument> args;
                    TaskModel tool;
                    if (!function.jvmArgs().isEmpty()) {
                        TaskModel.Tool toolModel;
                        tool = toolModel = new TaskModel.Tool(step.name(), List.of());

                        for (var arg : function.jvmArgs()) {
                            toolModel.args.add(processArgument(downloadInputs, arg, step, source, listLibrariesName));
                        }

                        toolModel.args.add(new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("-jar"))));
                        toolModel.args.add(new Argument.FileInput(null, new Input.DirectInput(Value.artifact(function.version())), PathSensitivity.NONE));
                        args = toolModel.args;
                    } else {
                        TaskModel.DaemonExecutedTool toolModel;
                        tool = toolModel = new TaskModel.DaemonExecutedTool(step.name(), List.of(), new Input.DirectInput(Value.artifact(function.version())));
                        args = toolModel.args;
                    }
                    if (isVineflower(function) && function.args().stream().noneMatch(s -> s.startsWith("-thr="))) {
                        args.add(processArgument(downloadInputs, "-thr="+SystemSpecsFinder.recommendedThreads(), step, source, listLibrariesName));
                    }
                    for (var arg : function.args()) {
                        args.add(processArgument(downloadInputs, arg, step, source, listLibrariesName));
                    }

                    if (isVineflower(function)) {
                        tool.parallelism = "decompile";
                        Output byName = null;
                        Output otherwise = null;
                        for (var arg : args) {
                            if (arg instanceof Argument.FileOutput output) {
                                if (output.name.equals("output")) {
                                    byName = new Output(step.name(), "output");
                                }
                                otherwise = vineflowerOutput;
                            }
                        }
                        vineflowerOutput = byName == null ? otherwise : byName;
                    }

                    yield tool;
                }
            };
            if (task != null) {
                config.tasks.add(task);
            }
        }

        config.tasks.add(new TaskModel.DownloadAssets("downloadAssets", downloadJsonInput));
        config.aliases.put("assets", new Output("downloadAssets", "properties"));

        Output sourcesTask = new Output("patch", "output");

        List<Output> sourcesStubs = new ArrayList<>();

        boolean useJst = options.accessTransformersParameter != null || options.injectedInterfacesParameter != null || options.parchmentDataParameter != null || !options.additionalJst.isEmpty();
        if (useJst) {
            var jst = new TaskModel.Jst(
                "generated_jstTransform",
                List.of(
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--enable-linemapper"))),
                    new Argument.FileOutput("--line-map-out={}", "linemap", "txt")
                ),
                new Input.TaskInput(sourcesTask),
                List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))),
                List.of(new Input.ListInput(List.of(new Input.DirectInput(Value.tool("linemapper-jst")))))
            );
            jst.parallelism = "jst";

            jst.binaryInput = new Input.TaskInput(new Output("rename", "output"));

            if (options.accessTransformersParameter != null) {
                jst.accessTransformers = new Input.ParameterInput(options.accessTransformersParameter);
            }
            if (options.injectedInterfacesParameter != null) {
                jst.interfaceInjection = new Input.ParameterInput(options.injectedInterfacesParameter);
            }
            if (options.parchmentDataParameter != null) {
                jst.parchmentData = new MappingsSource.File(new Input.ParameterInput(options.parchmentDataParameter));
            }
            for (var additionalJst : options.additionalJst) {
                jst.executionClasspath.addAll(additionalJst.classpath());
                jst.args.addAll(additionalJst.arguments());
            }

            config.tasks.add(jst);
            sourcesTask = new Output("generated_jstTransform", "output");
            sourcesStubs.add(new Output("generated_jstTransform", "stubs"));
        }

        Output binariesTask = new Output("rename", "output");
        if (options.recompile) {
            // Make recompile task
            var recompile = new TaskModel.Compile(
                "recompile",
                List.of(
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--release"))),
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue(source.javaTarget() + ""))), // Target the release the neoform config targets
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("-proc:none"))), // No APs in Minecraft's sources
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("-nowarn"))), // We'll handle the diagnostics ourselves
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("-g"))), // Gradle does it...
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("-XDuseUnsharedTable=true"))), // Gradle does it?
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("-implicit:none"))) // If we inject stubs, don't output those
                ),
                new Input.TaskInput(sourcesTask),
                List.of(new Input.ListInput(
                    sourcesStubs.stream().map(o -> (Input) new Input.TaskInput(o)).toList()
                )),
                List.of(new Input.TaskInput(new Output(listLibrariesName, "output")))
            );
            config.tasks.add(recompile);
            binariesTask = new Output("recompile", "output");
        } else {
            if (options.parchmentDataParameter != null) {
                // TODO: apply binary-level parchment transforms, for better debug info
            }

            if (options.injectedInterfacesParameter != null) {
                var injectInterfaces = new TaskModel.InterfaceInjection(
                    "generated_interfaceInjection",
                    new Input.TaskInput(binariesTask),
                    new Input.ParameterInput(options.injectedInterfacesParameter),
                    List.of(new Input.TaskInput(new Output(listLibrariesName, "output")))
                );
                config.tasks.add(injectInterfaces);
                binariesTask = new Output("generated_interfaceInjection", "output");
            }

            if (options.accessTransformersParameter != null) {
                config.tasks.add(new TaskModel.DaemonExecutedTool(
                    "generated_accessTransformers",
                    List.of(
                        Argument.direct("--inJar"),
                        new Argument.FileInput(null, new Input.TaskInput(binariesTask), PathSensitivity.NONE),
                        Argument.direct("--outJar"),
                        new Argument.FileOutput(null, "output", "jar"),
                        new Argument.Zip("--atFile={}", List.of(new Input.ParameterInput(options.accessTransformersParameter)), PathSensitivity.NONE)
                    ),
                    new Input.DirectInput(Value.tool("accesstransformers"))
                ));
                binariesTask = new Output("generated_accessTransformers", "output");
            }
        }

        // Capture this without line number modification if possible
        config.aliases.put("binarySourceIndependent", binariesTask);

        if (options.fixLineNumbers) {
            if (options.recompile) {
                throw new IllegalArgumentException("Cannot fix line numbers and recompile in the same neoform task graph -- binary output would be ambiguous");
            }

            var fixLineNumbers = new TaskModel.DaemonExecutedTool(
                "generated_fixLineNumbers",
                List.of(
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--input"))),
                    new Argument.FileInput(null, new Input.TaskInput(binariesTask), PathSensitivity.NONE),
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--output"))),
                    new Argument.FileOutput(null, "output", "jar")
                ),
                new Input.DirectInput(Value.tool("linemapper"))
            );

            if (vineflowerOutput != null) {
                fixLineNumbers.args.add(new Argument.FileInput("--vineflower={}", new Input.TaskInput(vineflowerOutput), PathSensitivity.NONE));
            }

            if (patches != null) {
                fixLineNumbers.args.add(new Argument.FileInput("--patches={}", new Input.TaskInput(patches), PathSensitivity.NONE));
            }

            if (useJst) {
                fixLineNumbers.args.add(new Argument.FileInput("--line-maps={}", new Input.TaskInput(new Output("jstTransform", "linemap")), PathSensitivity.NONE));
            }

            config.tasks.add(fixLineNumbers);
            binariesTask = new Output("generated_fixLineNumbers", "output");
        }

        config.aliases.put("sources", sourcesTask);
        config.aliases.put("binary", binariesTask);

        // merge resources
        switch (options.distribution) {
            case CLIENT -> config.aliases.put("resources", new Output("stripClient", "output"));
            case SERVER -> config.aliases.put("resources", new Output("stripServer", "output"));
            case JOINED -> {
                config.tasks.add(new TaskModel.InjectSources(
                    "generated_mergedResources",
                    stripTasks.stream().<Input>map(s -> new Input.TaskInput(new Output(s, "resources"))).toList()
                ));
                config.aliases.put("resources", new Output("generated_mergedResources", "output"));
            }
        }

        return config;
    }

    private static Argument processArgument(Map<String, Input> inputs, String arg, NeoFormStep step, NeoFormConfig fullConfig, String listLibrariesName) {
        if (arg.startsWith("{") && arg.endsWith("}")) {
            var name = arg.substring(1, arg.length() - 1);
            return switch (name) {
                case "libraries" -> new Argument.LibrariesFile(
                    null,
                    List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))),
                    new InputValue.DirectInput(new Value.DirectStringValue("-e="))
                    );
                case "version" -> new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue(fullConfig.version())));
                default -> {
                    var stepValue = step.values().get(name);
                    if (stepValue != null) {
                        yield new Argument.FileInput(null, parseStepInput(inputs, step, name), PathSensitivity.NONE);
                    } else {
                        if (fullConfig.data().containsKey(name)) {
                            var input = new Input.TaskInput(new Output("generated_retrieve_" + name, "output"));
                            yield new Argument.FileInput(null, input, PathSensitivity.NONE);
                        } else {
                            // Special-case the output extension for mergeMappings here... NeoForm's format is silly
                            yield new Argument.FileOutput(null, name, step.type().equals("mergeMappings") ? "tsrg" : "jar");
                        }
                    }
                }
            };
        } else {
            var function = fullConfig.functions().get(step.type());
            if (isVineflower(function)) {
                arg = arg.replace("TRACE", "WARN");
            }

            if (arg.startsWith("-Xmx")) {
                var rest = arg.substring(4);
                var systemProp = "dev.lukebemish.taskgraphrunner."+step.type()+".maxHeap";
                if (isVineflower(function)) {
                    rest = String.valueOf(SystemSpecsFinder.recommendedMemory());
                }
                return new Argument.Untracked("-Xmx{}", new InputValue.DirectInput(new Value.SystemPropertyValue(systemProp, rest)));
            } else if (arg.startsWith("-Xms")) {
                var rest = arg.substring(4);
                var systemProp = "dev.lukebemish.taskgraphrunner."+step.type()+".initialHeap";
                return new Argument.Untracked("-Xms{}", new InputValue.DirectInput(new Value.SystemPropertyValue(systemProp, rest)));
            } else if (isVineflower(function) && arg.startsWith("-thr=")) {
                var rest = arg.substring(5);
                var systemProp = "dev.lukebemish.taskgraphrunner."+step.type()+".maxThreads";
                return new Argument.Untracked("-thr={}", new InputValue.DirectInput(new Value.SystemPropertyValue(systemProp, rest)));
            }

            return new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue(arg)));
        }
    }

    private static boolean isVineflower(NeoFormFunction function) {
        return function.version().startsWith("org.vineflower:vineflower:");
    }

    private static Input parseStepInput(Map<String, Input> inputs, NeoFormStep step, String inputName) {
        var value = step.values().get(inputName);
        if (value.startsWith("{") && value.endsWith("}")) {
            var name = value.substring(1, value.length() - 1);
            if (name.endsWith("Output")) {
                var start = name.substring(0, name.length() - "Output".length());
                if (inputs.containsKey(start)) {
                    return inputs.get(start);
                }
                return new Input.TaskInput(new Output(start, "output"));
            }
            throw new IllegalArgumentException("Unsure how to fill variable `" + name + "` in step `"+step.name() + "`");
        }
        return new Input.DirectInput(new Value.DirectStringValue(value));
    }
}
