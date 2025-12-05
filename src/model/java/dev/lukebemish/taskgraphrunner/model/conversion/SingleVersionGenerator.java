package dev.lukebemish.taskgraphrunner.model.conversion;

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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class SingleVersionGenerator {
    private SingleVersionGenerator() {}

    public static final class Options {
        private final @Nullable String accessTransformersParameter;
        private final @Nullable String injectedInterfacesParameter;
        private final Distribution distribution;
        private final @Nullable SidedAnnotation sidedAnnotation;
        private final @Nullable String mappingsParameter;
        private final @Nullable Input versionJson;
        private final @Nullable Input clientJar;
        private final @Nullable Input serverJar;
        private final @Nullable Input clientMappings;
        private final List<AdditionalJst> additionalJst = new ArrayList<>();
        private final boolean remap;

        public enum SidedAnnotation implements Serializable {
            CPW("CPW"),
            NMF("NMF"),
            NEO("API"),
            FABRIC("FABRIC");

            private final String argument;

            SidedAnnotation(String argument) {
                this.argument = argument;
            }

            private String argument() {
                return argument;
            }
        }

        private Options(@Nullable String accessTransformersParameter, @Nullable String injectedInterfacesParameter, Distribution distribution, @Nullable SidedAnnotation sidedAnnotation, @Nullable String mappingsParameter, @Nullable Input versionJson, @Nullable Input clientJar, @Nullable Input serverJar, @Nullable Input clientMappings, boolean remap) {
            this.accessTransformersParameter = accessTransformersParameter;
            this.injectedInterfacesParameter = injectedInterfacesParameter;
            this.distribution = distribution;
            this.sidedAnnotation = sidedAnnotation;
            this.mappingsParameter = mappingsParameter;
            this.versionJson = versionJson;
            this.clientJar = clientJar;
            this.serverJar = serverJar;
            this.clientMappings = clientMappings;
            this.remap = remap;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String accessTransformersParameter = null;
            private String injectedInterfacesParameter = null;
            private Distribution distribution = Distribution.JOINED;
            private SidedAnnotation sidedAnnotation;
            private @Nullable String mappingsParameter = null;
            private @Nullable Input versionJson = null;
            private @Nullable Input clientJar = null;
            private @Nullable Input serverJar = null;
            private @Nullable Input clientMappings = null;
            private final List<AdditionalJst> additionalJst = new ArrayList<>();
            private boolean remap = true;

            private Builder() {}

            public Builder accessTransformersParameter(String accessTransformersParameter) {
                this.accessTransformersParameter = accessTransformersParameter;
                return this;
            }

            public Builder additionalJst(AdditionalJst additionalJst) {
                this.additionalJst.add(additionalJst);
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
                if (!this.remap) {
                    throw new IllegalStateException("Cannot set client mappings when remapping is disabled");
                }
                return this;
            }

            public Builder interfaceInjectionDataParameter(String injectedInterfacesParameter) {
                this.injectedInterfacesParameter = injectedInterfacesParameter;
                return this;
            }

            public Builder distribution(Distribution distribution) {
                this.distribution = distribution;
                return this;
            }

            public Builder sidedAnnotation(@Nullable SidedAnnotation sidedAnnotation) {
                this.sidedAnnotation = sidedAnnotation;
                return this;
            }

            public Builder mappingsParameter(String mappingsParameter) {
                this.mappingsParameter = mappingsParameter;
                return this;
            }

            public Builder noRemap() {
                this.remap = false;
                if (this.clientMappings != null) {
                    throw new IllegalStateException("Cannot set client mappings when remapping is disabled");
                }
                return this;
            }

            public Builder remap() {
                this.remap = true;
                return this;
            }

            public Options build() {
                var options = new Options(accessTransformersParameter, injectedInterfacesParameter, distribution, sidedAnnotation, mappingsParameter, versionJson, clientJar, serverJar, clientMappings, remap);
                options.additionalJst.addAll(additionalJst);
                return options;
            }
        }
    }

    public static Config convert(String version, Options options) throws IOException {
        var config = new Config();

        Input versionJson;
        if (options.versionJson == null) {
            var downloadManifestName = "downloadManifest";
            var downloadJsonName = "downloadJson";
            config.tasks.add(new TaskModel.DownloadManifest(downloadManifestName));
            config.tasks.add(new TaskModel.DownloadJson(downloadJsonName, new InputValue.DirectInput(new Value.DirectStringValue(version)), new Input.TaskInput(new Output(downloadManifestName, "output"))));
            versionJson = new Input.TaskInput(new Output(downloadJsonName, "output"));
        } else {
            versionJson = options.versionJson;
        }
        var listLibrariesName = "listLibraries";
        config.tasks.add(new TaskModel.ListClasspath(listLibrariesName, versionJson, null));

        config.tasks.add(new TaskModel.DownloadAssets("downloadAssets", versionJson));
        config.aliases.put("assets", new Output("downloadAssets", "properties"));

        Output merged = switch (options.distribution) {
            case CLIENT -> {
                Input clientJar;
                if (options.clientJar == null) {
                    config.tasks.add(new TaskModel.DownloadDistribution("downloadClient", new InputValue.DirectInput(new Value.DirectStringValue("client")), versionJson));
                    clientJar = new Input.TaskInput(new Output("downloadClient", "output"));
                } else {
                    clientJar = options.clientJar;
                }
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripClient",
                    clientJar,
                    null
                ));
                config.aliases.put("resources", new Output("stripClient", "resources"));
                yield new Output("stripClient", "output");
            }
            case SERVER -> {
                Input serverJar;
                if (options.serverJar == null) {
                    config.tasks.add(new TaskModel.DownloadDistribution("downloadServer", new InputValue.DirectInput(new Value.DirectStringValue("server")), versionJson));
                    serverJar = new Input.TaskInput(new Output("downloadServer", "output"));
                } else {
                    serverJar = options.serverJar;
                }
                config.tasks.add(new TaskModel.RetrieveData(
                    "extractServer",
                    serverJar,
                    new InputValue.DirectInput(new Value.DirectStringValue("META-INF/versions/"+version+"/server-"+version+".jar"))
                ));
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripServer",
                    new Input.TaskInput(new Output("extractServer", "output")),
                    null
                ));
                config.aliases.put("resources", new Output("stripServer", "resources"));
                yield new Output("extractServer", "output");
            }
            case JOINED -> {
                Input clientJar;
                Input serverJar;
                if (options.clientJar == null) {
                    config.tasks.add(new TaskModel.DownloadDistribution("downloadClient", new InputValue.DirectInput(new Value.DirectStringValue("client")), versionJson));
                    clientJar = new Input.TaskInput(new Output("downloadClient", "output"));
                } else {
                    clientJar = options.clientJar;
                }
                if (options.serverJar == null) {
                    config.tasks.add(new TaskModel.DownloadDistribution("downloadServer", new InputValue.DirectInput(new Value.DirectStringValue("server")), versionJson));
                    serverJar = new Input.TaskInput(new Output("downloadServer", "output"));
                } else {
                    serverJar = options.serverJar;
                }
                config.tasks.add(new TaskModel.RetrieveData(
                    "extractServer",
                    serverJar,
                    new InputValue.DirectInput(new Value.DirectStringValue("META-INF/versions/"+version+"/server-"+version+".jar"))
                ));
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripClient",
                    clientJar,
                    null
                ));
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripServer",
                    new Input.TaskInput(new Output("extractServer", "output")),
                    null
                ));
                config.tasks.add(new TaskModel.DaemonExecutedTool(
                    "merge",
                    List.of(
                        Argument.direct("--client"),
                        new Argument.FileInput(null, new Input.TaskInput(new Output("stripClient", "output")), PathSensitivity.NONE),
                        Argument.direct("--server"),
                        new Argument.FileInput(null, new Input.TaskInput(new Output("stripServer", "output")), PathSensitivity.NONE),
                        Argument.direct("--ann"),
                        options.sidedAnnotation == null ? Argument.direct(version) : Argument.direct(options.sidedAnnotation.argument()),
                        Argument.direct("--output"),
                        new Argument.FileOutput(null, "output", "jar"),
                        Argument.direct("--inject"),
                        Argument.direct("false")
                    ),
                    new Input.DirectInput(Value.tool("mergetool"))
                ));
                config.tasks.add(new TaskModel.InjectSources(
                    "mergeResources",
                    List.of(
                        new Input.TaskInput(new Output("stripClient", "resources")),
                        new Input.TaskInput(new Output("stripServer", "resources"))
                    )
                ));
                config.aliases.put("resources", new Output("mergeResources", "output"));
                yield new Output("merge", "output");
            }
        };

        config.aliases.put("binaryObf", merged);

        List<Output> additionalClasspath = new ArrayList<>();

        // rename the merged jar
        Output originalBinaries;

        if (options.remap) {
            Input downloadClientMappings;
            if (options.clientMappings == null) {
                config.tasks.add(new TaskModel.DownloadMappings("downloadClientMappings", new InputValue.DirectInput(new Value.DirectStringValue("client")), versionJson));
                downloadClientMappings = new Input.TaskInput(new Output("downloadClientMappings", "output"));
            } else {
                downloadClientMappings = options.clientMappings;
            }
            boolean reverseMappings = true;
            Input mappingsTaskOutput;
            if (options.mappingsParameter != null) {
                reverseMappings = false;
                mappingsTaskOutput = new Input.ParameterInput(options.mappingsParameter);
            } else {
                mappingsTaskOutput = downloadClientMappings;
            }
            var renameTask = new TaskModel.DaemonExecutedTool(
                "rename",
                List.of(
                    Argument.direct("--input"),
                    new Argument.FileInput(null, new Input.TaskInput(merged), PathSensitivity.NONE),
                    Argument.direct("--output"),
                    new Argument.FileOutput(null, "output", "jar"),
                    Argument.direct("--map"),
                    new Argument.FileInput(null, mappingsTaskOutput, PathSensitivity.NONE),
                    Argument.direct("--cfg"),
                    new Argument.LibrariesFile(null, List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))), new InputValue.DirectInput(new Value.DirectStringValue("-e="))),
                    Argument.direct("--ann-fix"),
                    Argument.direct("--ids-fix"),
                    Argument.direct("--src-fix"),
                    Argument.direct("--record-fix"),
                    Argument.direct("--unfinal-params")
                ),
                new Input.DirectInput(Value.tool("autorenamingtool"))
            );
            config.tasks.add(renameTask);
            if (reverseMappings) {
                renameTask.args.add(Argument.direct("--reverse"));
            }
            originalBinaries = new Output("rename", "output");
        } else {
            originalBinaries = merged;
        }

        Output binariesTask = originalBinaries;

        // we do binary-level AT and interface injection
        if (options.injectedInterfacesParameter != null) {
            List<Input> classpath = new ArrayList<>();
            classpath.add(new Input.TaskInput(new Output(listLibrariesName, "output")));
            for (Output stubs : additionalClasspath) {
                classpath.add(new Input.ListInput(List.of(new Input.TaskInput(stubs))));
            }

            config.tasks.add(new TaskModel.InterfaceInjection(
                "interfaceInjection",
                new Input.TaskInput(binariesTask),
                new Input.ParameterInput(options.injectedInterfacesParameter),
                classpath
            ));
            binariesTask = new Output("interfaceInjection", "output");
            // We do not need to add II stubs to the decomp classpath -- they're not there yet.
        }

        if (options.accessTransformersParameter != null) {
            config.tasks.add(new TaskModel.DaemonExecutedTool(
                "accessTransformers",
                List.of(
                    Argument.direct("--inJar"),
                    new Argument.FileInput(null, new Input.TaskInput(binariesTask), PathSensitivity.NONE),
                    Argument.direct("--outJar"),
                    new Argument.FileOutput(null, "output", "jar"),
                    new Argument.Zip("--atFile={}", List.of(new Input.ParameterInput(options.accessTransformersParameter)), PathSensitivity.NONE)
                ),
                new Input.DirectInput(Value.tool("accesstransformers"))
            ));
            binariesTask = new Output("accessTransformers", "output");
        }

        List<Input> decompileClasspath = new ArrayList<>();
        decompileClasspath.add(new Input.TaskInput(new Output(listLibrariesName, "output")));
        for (Output stubs : additionalClasspath) {
            decompileClasspath.add(new Input.ListInput(List.of(new Input.TaskInput(stubs))));
        }
        var decompileTask = new TaskModel.Tool(
            "decompile",
            List.of(
                new Argument.Untracked("-Xmx{}", new InputValue.DirectInput(new Value.SystemPropertyValue("dev.lukebemish.taskgraphrunner.decompile.maxHeap", String.valueOf(SystemSpecsFinder.recommendedMemory())))),
                Argument.direct("-jar"),
                new Argument.FileInput(null, new Input.DirectInput(Value.tool("vineflower")), PathSensitivity.NONE),
                new Argument.Untracked("-thr={}", new InputValue.DirectInput(new Value.SystemPropertyValue("dev.lukebemish.taskgraphrunner.decompile.maxThreads", String.valueOf(SystemSpecsFinder.recommendedThreads())))),
                Argument.direct("--decompile-inner"),
                Argument.direct("--remove-bridge"),
                Argument.direct("--decompile-generics"),
                Argument.direct("--ascii-strings"),
                Argument.direct("--remove-synthetic"),
                Argument.direct("--include-classpath"),
                Argument.direct("--variable-renaming=jad"),
                Argument.direct("--ignore-invalid-bytecode"),
                Argument.direct("--bytecode-source-mapping"),
                Argument.direct("--dump-code-lines"),
                Argument.direct("--indent-string=    "),
                Argument.direct("--log-level=WARN"),
                Argument.direct("-cfg"),
                new Argument.LibrariesFile(null, decompileClasspath, new InputValue.DirectInput(new Value.DirectStringValue("-e="))),
                new Argument.FileInput(null, new Input.TaskInput(originalBinaries), PathSensitivity.NONE),
                new Argument.FileOutput(null, "output", "jar")
            )
        );
        decompileTask.parallelism = "decompile";
        config.tasks.add(decompileTask);

        Output sourcesTask = new Output("decompile", "output");

        boolean useJst = options.mappingsParameter != null || options.injectedInterfacesParameter != null || options.accessTransformersParameter != null || !options.additionalJst.isEmpty();
        if (useJst) {
            var jst = new TaskModel.Jst(
                "jstTransform",
                List.of(
                    new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--enable-linemapper"))),
                    new Argument.FileOutput("--line-map-out={}", "linemap", "txt")
                ),
                new Input.TaskInput(sourcesTask),
                List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))),
                List.of(new Input.ListInput(List.of(new Input.DirectInput(Value.tool("linemapper-jst")))))
            );
            jst.parallelism = "jst";

            jst.binaryInput = new Input.TaskInput(originalBinaries);

            if (options.mappingsParameter != null) {
                jst.parchmentData = new MappingsSource.File(new Input.ParameterInput(options.mappingsParameter));
            }
            if (options.injectedInterfacesParameter != null) {
                jst.interfaceInjection = new Input.ParameterInput(options.injectedInterfacesParameter);
            }
            if (options.accessTransformersParameter != null) {
                jst.accessTransformers = new Input.ParameterInput(options.accessTransformersParameter);
            }
            for (var additionalJst : options.additionalJst) {
                jst.executionClasspath.addAll(additionalJst.classpath());
                jst.args.addAll(additionalJst.arguments());
            }

            config.tasks.add(jst);
            sourcesTask = new Output("jstTransform", "output");
        }

        config.aliases.put("binarySourceIndependent", binariesTask);

        var fixLineNumbers = new TaskModel.DaemonExecutedTool(
            "fixLineNumbers",
            List.of(
                new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--input"))),
                new Argument.FileInput(null, new Input.AliasInput("binarySourceIndependent"), PathSensitivity.NONE),
                new Argument.ValueInput(null, new InputValue.DirectInput(new Value.DirectStringValue("--output"))),
                new Argument.FileOutput(null, "output", "jar")
            ),
            new Input.DirectInput(Value.tool("linemapper"))
        );

        fixLineNumbers.args.add(new Argument.FileInput("--vineflower={}", new Input.TaskInput(new Output("decompile", "output")), PathSensitivity.NONE));

        if (useJst) {
            fixLineNumbers.args.add(new Argument.FileInput("--line-maps={}", new Input.TaskInput(new Output("jstTransform", "linemap")), PathSensitivity.NONE));
        }

        config.tasks.add(fixLineNumbers);
        binariesTask = new Output("fixLineNumbers", "output");

        config.aliases.put("sources", sourcesTask);
        config.aliases.put("binary", binariesTask);

        return config;
    }
}
