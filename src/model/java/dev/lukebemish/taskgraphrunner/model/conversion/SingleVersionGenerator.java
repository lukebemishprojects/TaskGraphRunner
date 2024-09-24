package dev.lukebemish.taskgraphrunner.model.conversion;

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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class SingleVersionGenerator {
    private SingleVersionGenerator() {}

    public static final class Options {
        private final @Nullable String accessTransformersParameter;
        private final @Nullable String injectedInterfacesParameter;
        private final @Nullable String parchmentDataParameter;
        private final Distribution distribution;
        private final @Nullable SidedAnnotation sidedAnnotation;

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

        private Options(@Nullable String accessTransformersParameter, @Nullable String injectedInterfacesParameter, @Nullable String parchmentDataParameter, Distribution distribution, @Nullable SidedAnnotation sidedAnnotation) {
            this.accessTransformersParameter = accessTransformersParameter;
            this.injectedInterfacesParameter = injectedInterfacesParameter;
            this.parchmentDataParameter = parchmentDataParameter;
            this.distribution = distribution;
            this.sidedAnnotation = sidedAnnotation;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String accessTransformersParameter = null;
            private String injectedInterfacesParameter = null;
            private String parchmentDataParameter = null;
            private Distribution distribution = Distribution.JOINED;
            private SidedAnnotation sidedAnnotation;

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

            public Builder distribution(Distribution distribution) {
                this.distribution = distribution;
                return this;
            }

            public Builder sidedAnnotation(@Nullable SidedAnnotation sidedAnnotation) {
                this.sidedAnnotation = sidedAnnotation;
                return this;
            }

            public Options build() {
                return new Options(accessTransformersParameter, injectedInterfacesParameter, parchmentDataParameter, distribution, sidedAnnotation);
            }
        }
    }

    public static Config convert(String version, Options options) throws IOException {
        var config = new Config();

        var downloadManifestName = "downloadManifest";
        var downloadJsonName = "downloadJson";
        var listLibrariesName = "listLibraries";
        config.tasks.add(new TaskModel.DownloadManifest(downloadManifestName));
        config.tasks.add(new TaskModel.DownloadJson(downloadJsonName, new Input.DirectInput(new Value.StringValue(version)), new Input.TaskInput(new Output(downloadManifestName, "output"))));
        config.tasks.add(new TaskModel.ListClasspath(listLibrariesName, new Input.TaskInput(new Output(downloadJsonName, "output")), null));

        config.tasks.add(new TaskModel.DownloadAssets("downloadAssets", new Input.TaskInput(new Output(downloadJsonName, "output"))));
        config.aliases.put("assets", new Output("downloadAssets", "properties"));

        Output merged = switch (options.distribution) {
            case CLIENT -> {
                config.tasks.add(new TaskModel.DownloadDistribution("downloadClient", new Input.DirectInput(new Value.StringValue("client")), new Input.TaskInput(new Output(downloadJsonName, "output"))));
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripClient",
                    new Input.TaskInput(new Output("downloadClient", "output")),
                    null
                ));
                config.aliases.put("resources", new Output("stripClient", "resources"));
                yield new Output("stripClient", "output");
            }
            case SERVER -> {
                config.tasks.add(new TaskModel.DownloadDistribution("downloadServer", new Input.DirectInput(new Value.StringValue("server")), new Input.TaskInput(new Output(downloadJsonName, "output"))));
                config.tasks.add(new TaskModel.RetrieveData(
                    "extractServer",
                    new Input.TaskInput(new Output("downloadServer", "output")),
                    new Input.DirectInput(new Value.StringValue("META-INF/versions/"+version+"/server-"+version+".jar"))
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
                config.tasks.add(new TaskModel.DownloadDistribution("downloadClient", new Input.DirectInput(new Value.StringValue("client")), new Input.TaskInput(new Output(downloadJsonName, "output"))));
                config.tasks.add(new TaskModel.DownloadDistribution("downloadServer", new Input.DirectInput(new Value.StringValue("server")), new Input.TaskInput(new Output(downloadJsonName, "output"))));
                config.tasks.add(new TaskModel.RetrieveData(
                    "extractServer",
                    new Input.TaskInput(new Output("downloadServer", "output")),
                    new Input.DirectInput(new Value.StringValue("META-INF/versions/"+version+"/server-"+version+".jar"))
                ));
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripClient",
                    new Input.TaskInput(new Output("downloadClient", "output")),
                    null
                ));
                config.tasks.add(new TaskModel.SplitClassesResources(
                    "stripServer",
                    new Input.TaskInput(new Output("extractServer", "output")),
                    null
                ));
                config.tasks.add(new TaskModel.Tool(
                    "merge",
                    List.of(
                        Argument.direct("-jar"),
                        new Argument.FileInput(null, new Input.DirectInput(Value.tool("mergetool")), PathSensitivity.NONE),
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
                    )
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

        List<Output> additionalClasspath = new ArrayList<>();

        // rename the merged jar
        config.tasks.add(new TaskModel.DownloadMappings("downloadClientMappings", new Input.DirectInput(new Value.StringValue("client")), new Input.TaskInput(new Output(downloadJsonName, "output"))));
        config.tasks.add(new TaskModel.Tool(
            "rename",
            List.of(
                Argument.direct("-jar"),
                new Argument.FileInput(null, new Input.DirectInput(Value.tool("autorenamingtool")), PathSensitivity.NONE),
                Argument.direct("--input"),
                new Argument.FileInput(null, new Input.TaskInput(merged), PathSensitivity.NONE),
                Argument.direct("--output"),
                new Argument.FileOutput(null, "output", "jar"),
                Argument.direct("--map"),
                new Argument.FileInput(null, new Input.TaskInput(new Output("downloadClientMappings", "output")), PathSensitivity.NONE),
                Argument.direct("--cfg"),
                new Argument.LibrariesFile(null, List.of(new Input.TaskInput(new Output(listLibrariesName, "output"))), new Input.DirectInput(new Value.StringValue("-e="))),
                Argument.direct("--ann-fix"),
                Argument.direct("--ids-fix"),
                Argument.direct("--src-fix"),
                Argument.direct("--record-fix"),
                Argument.direct("--unfinal-params"),
                Argument.direct("--reverse")
            )
        ));

        Output binariesTask = new Output("rename", "output");

        // we do source-level AT and interface injection
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
            additionalClasspath.add(new Output("interfaceInjection", "stubs"));
        }

        if (options.accessTransformersParameter != null) {
            config.tasks.add(new TaskModel.Tool(
                "accessTransformers",
                List.of(
                    Argument.direct("-jar"),
                    new Argument.FileInput(null, new Input.DirectInput(Value.tool("accesstransformers")), PathSensitivity.NONE),
                    Argument.direct("--inFile"),
                    new Argument.FileInput(null, new Input.TaskInput(binariesTask), PathSensitivity.NONE),
                    Argument.direct("--outFile"),
                    new Argument.FileOutput(null, "output", "jar"),
                    new Argument.Zip("--atFile={}", List.of(new Input.ParameterInput(options.accessTransformersParameter)), PathSensitivity.NONE)
                )
            ));
            binariesTask = new Output("accessTransformers", "output");
        }

        List<Input> decompileClasspath = new ArrayList<>();
        decompileClasspath.add(new Input.TaskInput(new Output(listLibrariesName, "output")));
        for (Output stubs : additionalClasspath) {
            decompileClasspath.add(new Input.ListInput(List.of(new Input.TaskInput(stubs))));
        }
        config.tasks.add(new TaskModel.Tool(
            "decompile",
            List.of(
                Argument.direct("-Xmx4G"),
                Argument.direct("-jar"),
                new Argument.FileInput(null, new Input.DirectInput(Value.tool("vineflower")), PathSensitivity.NONE),
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
                new Argument.LibrariesFile(null, decompileClasspath, new Input.DirectInput(new Value.StringValue("-e="))),
                new Argument.FileInput(null, new Input.TaskInput(binariesTask), PathSensitivity.NONE),
                new Argument.FileOutput(null, "output", "jar")
            )
        ));

        Output sourcesTask = new Output("decompile", "output");

        boolean useJst = options.parchmentDataParameter != null;
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

            if (options.parchmentDataParameter != null) {
                jst.parchmentData = new Input.ParameterInput(options.parchmentDataParameter);
            }

            config.tasks.add(jst);
            sourcesTask = new Output("jstTransform", "output");
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
