package dev.lukebemish.taskgraphrunner.model.neoform;

import com.google.gson.Gson;
import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Input;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NeoFormConversion {
    private NeoFormConversion() {}

    /**
     * Convert a neoform config zip into a task graph config.
     * @param neoFormConfig the path to the neoform config zip
     * @param distribution the distribution of the config to extract
     * @param selfReference how the config zip should be referenced in the generated config
     * @return a new task graph config
     */
    public static Config convert(Path neoFormConfig, String distribution, Value selfReference) throws IOException {
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

        for (var step : source.steps().getOrDefault(distribution, List.of())) {
            switch (step.type()) {
                case "downloadManifest" -> downloadManifestName = step.name();
                case "downloadJson" -> downloadJsonName = step.name();
                case "listLibraries" -> listLibrariesName = step.name();
                default -> {}
            }
        }

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
                    parseStepInput(step, "input"),
                    new Input.TaskInput(new Output("generated_retrieve_inject", "output"))
                );
                case "patch" -> new TaskModel.PatchSources(
                    step.name(),
                    parseStepInput(step, "input"),
                    new Input.TaskInput(new Output("generated_retrieve_patch", "output"))
                );
                default -> {
                    var function = source.functions().get(step.type());
                    if (function == null) {
                        throw new IllegalStateException("Unknown neoform step type: " + step.type());
                    }
                    var tool = new TaskModel.Tool(step.name(), List.of());

                    for (var arg : function.jvmArgs()) {
                        tool.args.add(processArgument(arg, step, listLibrariesName));
                    }

                    tool.args.add(new Argument.ValueInput(new Input.DirectInput(new Value.StringValue("-jar"))));
                    tool.args.add(new Argument.FileInput(new Input.DirectInput(Value.artifact(function.version())), PathSensitivity.NONE));

                    for (var arg : function.args()) {
                        tool.args.add(processArgument(arg, step, listLibrariesName));
                    }

                    yield tool;
                }
            };
            config.tasks.add(task);
        }

        return config;
    }

    private static Argument processArgument(String arg, NeoFormStep step, String listLibrariesName) {
        if (arg.startsWith("{") && arg.endsWith("}")) {
            var name = arg.substring(1, arg.length() - 1);
            if ("libraries".equals(name)) {
                return new Argument.Classpath(
                    new Input.TaskInput(new Output(listLibrariesName, "output")),
                    true
                );
            }
            var stepValue = step.values().get(name);
            if (stepValue != null) {
                return new Argument.FileInput(parseStepInput(step, name), PathSensitivity.NONE);
            } else {
                var input = new Input.TaskInput(new Output("generated_retrieve_" + name, "output"));
                return new Argument.FileInput(input, PathSensitivity.NONE);
            }
        } else {
            return new Argument.ValueInput(new Input.DirectInput(new Value.StringValue(arg)));
        }
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
