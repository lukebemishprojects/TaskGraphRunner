package dev.lukebemish.taskgraphrunner.model.conversion;

import com.google.gson.Gson;
import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Distribution;
import dev.lukebemish.taskgraphrunner.model.Input;
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
import java.util.List;
import java.util.Locale;
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

        private Options(@Nullable String accessTransformersParameter, @Nullable String injectedInterfacesParameter, @Nullable String parchmentDataParameter, boolean recompile, boolean fixLineNumbers, Distribution distribution) {
            this.accessTransformersParameter = accessTransformersParameter;
            this.injectedInterfacesParameter = injectedInterfacesParameter;
            this.parchmentDataParameter = parchmentDataParameter;
            this.recompile = recompile;
            this.fixLineNumbers = fixLineNumbers;
            this.distribution = distribution;
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

            private Builder() {}

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
                return new Options(accessTransformersParameter, injectedInterfacesParameter, parchmentDataParameter, recompile, fixLineNumbers, distribution);
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
                new Input.DirectInput(new Value.StringValue(path))
            ));
        }

        var downloadManifestName = "downloadManifest";
        var downloadJsonName = "downloadJson";
        var listLibrariesName = "listLibraries";
        var stripTasks = new ArrayList<String>();

        for (var step : source.steps().getOrDefault(distribution, List.of())) {
            switch (step.type()) {
                case "downloadManifest" -> downloadManifestName = step.name();
                case "downloadJson" -> downloadJsonName = step.name();
                case "listLibraries" -> listLibrariesName = step.name();
                case "strip" -> stripTasks.add(step.name());
                default -> {}
            }
        }

        Output patches = null;
        Output vineflowerOutput = null;

        for (var step : source.steps().getOrDefault(distribution, List.of())) {
            var task = switch (step.type()) {
                case "downloadManifest" -> new TaskModel.DownloadManifest(step.name());
                case "downloadJson" -> new TaskModel.DownloadJson(
                    step.name(),
                    new Input.DirectInput(new Value.StringValue(source.version())),
                    new Input.TaskInput(new Output(downloadManifestName, "output"))
                );
                case "downloadClient" -> new TaskModel.DownloadDistribution(
                    step.name(),
                    new Input.DirectInput(new Value.StringValue("client")),
                    new Input.TaskInput(new Output(downloadJsonName, "output"))
                );
                case "downloadServer" -> new TaskModel.DownloadDistribution(
                    step.name(),
                    new Input.DirectInput(new Value.StringValue("server")),
                    new Input.TaskInput(new Output(downloadJsonName, "output"))
                );
                case "downloadClientMappings" -> new TaskModel.DownloadMappings(
                    step.name(),
                    new Input.DirectInput(new Value.StringValue("client")),
                    new Input.TaskInput(new Output(downloadJsonName, "output"))
                );
                case "downloadServerMappings" -> new TaskModel.DownloadMappings(
                    step.name(),
                    new Input.DirectInput(new Value.StringValue("server")),
                    new Input.TaskInput(new Output(downloadJsonName, "output"))
                );
                case "strip" -> new TaskModel.SplitClassesResources(
                    step.name(),
                    parseStepInput(step, "input"),
                    null
                );
                case "listLibraries" -> new TaskModel.ListClasspath(
                    step.name(),
                    new Input.TaskInput(new Output(downloadJsonName, "output")),
                    new Input.ParameterInput("additionalLibraries")
                );
                case "inject" -> new TaskModel.InjectSources(
                    step.name(),
                    List.of(
                        parseStepInput(step, "input"),
                        new Input.TaskInput(new Output("generated_retrieve_inject", "output"))
                    )
                );
                case "patch" -> {
                    patches = new Output("generated_retrieve_patches", "output");
                    yield new TaskModel.PatchSources(
                        step.name(),
                        parseStepInput(step, "input"),
                        new Input.TaskInput(new Output("generated_retrieve_patches", "output"))
                    );
                }
                default -> {
                    var function = source.functions().get(step.type());
                    if (function == null) {
                        throw new IllegalStateException("Unknown neoform step type: " + step.type());
                    }
                    var tool = new TaskModel.Tool(step.name(), List.of());

                    for (var arg : function.jvmArgs()) {
                        tool.args.add(processArgument(arg, step, source, listLibrariesName));
                    }

                    tool.args.add(new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-jar"))));
                    tool.args.add(new Argument.FileInput(null, new Input.DirectInput(Value.artifact(function.version())), PathSensitivity.NONE));

                    for (var arg : function.args()) {
                        tool.args.add(processArgument(arg, step, source, listLibrariesName));
                    }

                    if (isVineflower(function)) {
                        Output byName = null;
                        Output otherwise = null;
                        for (var arg : tool.args) {
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
            config.tasks.add(task);
        }

        config.tasks.add(new TaskModel.DownloadAssets("downloadAssets", new Input.TaskInput(new Output(downloadJsonName, "output"))));
        config.aliases.put("assets", new Output("downloadAssets", "properties"));

        Output sourcesTask = new Output("patch", "output");

        List<Output> sourcesStubs = new ArrayList<>();

        boolean useJst = options.accessTransformersParameter != null || options.injectedInterfacesParameter != null || options.parchmentDataParameter != null;
        if (useJst) {
            var jst = new TaskModel.Jst(
                "jstTransform",
                List.of(),
                List.of(
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("--enable-linemapper"))),
                    new Argument.FileOutput("--line-map-out={}", "linemap", "txt")
                ),
                new Input.TaskInput(sourcesTask),
                List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))),
                List.of(new Input.ListInput(List.of(new Input.DirectInput(Value.tool("linemapper-jst")))))
            );

            if (options.accessTransformersParameter != null) {
                jst.accessTransformers = new Input.ParameterInput(options.accessTransformersParameter);
            }
            if (options.injectedInterfacesParameter != null) {
                jst.interfaceInjection = new Input.ParameterInput(options.injectedInterfacesParameter);
            }
            if (options.parchmentDataParameter != null) {
                jst.parchmentData = new Input.ParameterInput(options.parchmentDataParameter);
            }

            config.tasks.add(jst);
            sourcesTask = new Output("jstTransform", "output");
            sourcesStubs.add(new Output("jstTransform", "stubs"));
        }

        Output binariesTask = new Output("rename", "output");
        if (options.recompile) {
            // Make recompile task
            var recompile = new TaskModel.Compile(
                "recompile",
                List.of(
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("--release"))),
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue(source.javaTarget() + ""))), // Target the release the neoform config targets
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-proc:none"))), // No APs in Minecraft's sources
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-nowarn"))), // We'll handle the diagnostics ourselves
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-g"))), // Gradle does it...
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-XDuseUnsharedTable=true"))), // Gradle does it?
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-implicit:none"))) // If we inject stubs, don't output those
                ),
                new Input.TaskInput(sourcesTask),
                List.of(new Input.ListInput(
                    sourcesStubs.stream().map(o -> (Input) new Input.TaskInput(o)).toList()
                )),
                List.of(new Input.TaskInput(new Output(listLibrariesName, "output")))
            );
            config.tasks.add(recompile);
            binariesTask = new Output("recompile", "output");
        }

        if (!options.recompile && options.injectedInterfacesParameter != null) {
            var injectInterfaces = new TaskModel.InterfaceInjection(
                "interfaceInjection",
                new Input.TaskInput(binariesTask),
                new Input.ParameterInput(options.injectedInterfacesParameter),
                List.of(new Input.TaskInput(new Output(listLibrariesName, "output")))
            );
            config.tasks.add(injectInterfaces);
            binariesTask = new Output("interfaceInjection", "output");
        }

        if (options.fixLineNumbers) {
            if (options.recompile) {
                throw new IllegalArgumentException("Cannot fix line numbers and recompile in the same neoform task graph -- binary output would be ambiguous");
            }

            var fixLineNumbers = new TaskModel.Tool(
                "fixLineNumbers",
                List.of(
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("-jar"))),
                    new Argument.FileInput(null, new Input.DirectInput(Value.tool("linemapper")), PathSensitivity.NONE),
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("--input"))),
                    new Argument.FileInput(null, new Input.TaskInput(binariesTask), PathSensitivity.NONE),
                    new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue("--output"))),
                    new Argument.FileOutput(null, "output", "jar")
                )
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
            binariesTask = new Output("fixLineNumbers", "output");
        }

        config.aliases.put("sources", sourcesTask);
        config.aliases.put("binary", binariesTask);

        // merge resources
        config.tasks.add(new TaskModel.InjectSources(
            "mergedResources",
            stripTasks.stream().<Input>map(s -> new Input.TaskInput(new Output(s, "output"))).toList()
        ));
        config.aliases.put("resources", new Output("mergedResources", "resources"));

        return config;
    }

    private static Argument processArgument(String arg, NeoFormStep step, NeoFormConfig fullConfig, String listLibrariesName) {
        if (arg.startsWith("{") && arg.endsWith("}")) {
            var name = arg.substring(1, arg.length() - 1);
            return switch (name) {
                case "libraries" -> new Argument.LibrariesFile(
                    null,
                    List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))),
                    new Input.DirectInput(new Value.StringValue("-e="))
                    );
                case "version" -> new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue(fullConfig.version())));
                default -> {
                    var stepValue = step.values().get(name);
                    if (stepValue != null) {
                        yield new Argument.FileInput(null, parseStepInput(step, name), PathSensitivity.NONE);
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
            return new Argument.ValueInput(null, new Input.DirectInput(new Value.StringValue(arg)));
        }
    }

    private static boolean isVineflower(NeoFormFunction function) {
        return function.version().startsWith("org.vineflower:vineflower:");
    }

    private static Input parseStepInput(NeoFormStep step, String inputName) {
        var value = step.values().get(inputName);
        if (value.startsWith("{") && value.endsWith("}")) {
            var name = value.substring(1, value.length() - 1);
            if (name.endsWith("Output")) {
                var start = name.substring(0, name.length() - "Output".length());
                return new Input.TaskInput(new Output(start, "output"));
            }
            throw new IllegalArgumentException("Unsure how to fill variable `" + name + "` in step `"+step.name() + "`");
        }
        return new Input.DirectInput(new Value.StringValue(value));
    }
}
