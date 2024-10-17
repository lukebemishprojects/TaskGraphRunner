package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.GsonBuilder;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Distribution;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.model.conversion.SingleVersionGenerator;
import dev.lukebemish.taskgraphrunner.runtime.ArtifactManifest;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "vanilla", mixinStandardHelpOptions = true, description = "Generate a task graph file for a single MC version, without recompilation or patching.")
public class Vanilla implements Runnable {
    private final Main main;

    @CommandLine.Parameters(index = "0", description = "Version to generate a config for.")
    String version;

    @CommandLine.Parameters(index = "1", description = "Output config file.")
    Path target;

    @CommandLine.Option(names = "--distribution", description = "Distribution to extract.", required = true)
    String distribution;

    @CommandLine.Option(names = "--result", arity = "*", description = "Results, as <task>.<output>:<path> or <alias>:<path> pairs, to include in the generated config.")
    List<String> results = List.of();

    @CommandLine.Option(names = "--access-transformer", arity = "*", description = "Access transformer file path, as artifact:<artifact ID> or file:path form.")
    List<String> accessTransformers = List.of();

    @CommandLine.Option(names = "--interface-injection", arity = "*", description = "Interface injection data file path, as artifact:<artifact ID> or file:path form.")
    List<String> interfaceInjection = List.of();

    @CommandLine.Option(names = "--sided-annotation", description = "Annotation to use for sidedness.")
    SingleVersionGenerator.Options.SidedAnnotation sidedAnnotation;

    @CommandLine.Option(names = "--mappings", description = "Mappings to use, from obfuscated to named, as artifact:<artifact ID> or file:path form.")
    String mappings = null;

    Vanilla(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        try {
            var optionsBuilder = SingleVersionGenerator.Options.builder()
                .sidedAnnotation(sidedAnnotation);
            if (!accessTransformers.isEmpty()) {
                optionsBuilder.accessTransformersParameter("accessTransformers");
            }
            if (!interfaceInjection.isEmpty()) {
                optionsBuilder.interfaceInjectionDataParameter("interfaceInjection");
            }
            optionsBuilder.distribution(Distribution.valueOf(distribution.toUpperCase()));
            if (mappings != null) {
                optionsBuilder.mappingsParameter("mappings");
            }
            ArtifactManifest manifest = main.makeManifest();
            Config config = SingleVersionGenerator.convert(version, optionsBuilder.build());
            var workItem = new WorkItem();
            for (var result : results) {
                var split = result.split(":");
                if (split.length != 2) {
                    throw new IllegalArgumentException("Invalid result format, expected <alias>:<path> or <task>.<output>:path: " + result);
                }
                var taskOutput = split[0].split("\\.");
                if (taskOutput.length > 2) {
                    throw new IllegalArgumentException("Invalid result format, expected <alias>:<path> or <task>.<output>:path: " + result);
                } else if (taskOutput.length == 2) {
                    workItem.results.put(new WorkItem.Target.OutputTarget(new Output(taskOutput[0], taskOutput[1])), Path.of(split[1]));
                } else {
                    workItem.results.put(new WorkItem.Target.AliasTarget(taskOutput[0]), Path.of(split[1]));
                }
            }
            if (!accessTransformers.isEmpty()) {
                workItem.parameters.put("accessTransformers", new Value.ListValue(accessTransformers.stream().map(s -> (Value) new Value.StringValue(manifest.absolute(s))).toList()));
            }
            if (!interfaceInjection.isEmpty()) {
                workItem.parameters.put("interfaceInjection", new Value.ListValue(interfaceInjection.stream().map(s -> (Value) new Value.StringValue(manifest.absolute(s))).toList()));
            }
            if (mappings != null) {
                workItem.parameters.put("mappings", new Value.StringValue(manifest.absolute(mappings)));
            }
            config.workItems.add(workItem);

            try (var writer = Files.newBufferedWriter(target)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(config, writer);
                writer.write("\n"); // NOT the system newline -- Gson always uses \n (see FormattingStyle)
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
