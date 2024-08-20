package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.GsonBuilder;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.model.neoform.NeoFormConversion;
import dev.lukebemish.taskgraphrunner.runtime.ArtifactManifest;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "neoform", mixinStandardHelpOptions = true, description = "Convert a neoform config to a task graph.")
public class NeoForm implements Runnable {
    private final Main main;

    @CommandLine.Parameters(index = "0", description = "NeoForm zip file, as artifact:<artifact ID> or file:path form.")
    String zip;

    @CommandLine.Parameters(index = "1", description = "Output config file.")
    Path target;

    @CommandLine.Option(names = "--distribution", description = "Distribution to extract.", required = true)
    String distribution;

    @CommandLine.Option(names = "--result", arity = "*", description = "Results, as <task>.<output>:<path> pairs, to include in the generated config.")
    List<String> results = List.of();

    @CommandLine.Option(names = "--access-transformer", arity = "*", description = "Access transformer file path, as artifact:<artifact ID> or file:path form.")
    List<String> accessTransformers = List.of();

    @CommandLine.Option(names = "--interface-injection", arity = "*", description = "Interface injection data file path, as artifact:<artifact ID> or file:path form.")
    List<String> interfaceInjection = List.of();

    @CommandLine.Option(names = "--parchment-data", description = "Parchment data, as artifact:<artifact ID> or file:path form.")
    String parchmentData = null;

    @CommandLine.Option(names = "--recompile", description = "Whether the game should be recompiled.")
    boolean recompile = true;

    @CommandLine.Option(names = "--fix-line-numbers", description = "Whether to fix line numbers using vineflower output and patches.")
    boolean fixLineNumbers = false;

    NeoForm(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        try {
            var optionsBuilder = NeoFormConversion.Options.builder();
            if (!accessTransformers.isEmpty()) {
                optionsBuilder.accessTransformersParameter("accessTransformers");
            }
            if (!interfaceInjection.isEmpty()) {
                optionsBuilder.interfaceInjectionDataParameter("interfaceInjection");
            }
            if (parchmentData != null) {
                optionsBuilder.parchmentDataParameter("parchmentData");
            }
            optionsBuilder.recompile(recompile);
            optionsBuilder.fixLineNumbers(fixLineNumbers);
            ArtifactManifest manifest = new ArtifactManifest();
            for (var m : main.artifactManifests) {
                manifest.artifactManifest(m);
            }
            var zipPath = manifest.resolve(zip);
            Config config = NeoFormConversion.convert(zipPath, distribution, new Value.StringValue(manifest.absolute(zip)), optionsBuilder.build());
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
            if (parchmentData != null) {
                workItem.parameters.put("parchmentData", new Value.StringValue(manifest.absolute(parchmentData)));
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
