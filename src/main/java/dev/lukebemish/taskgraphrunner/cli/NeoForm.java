package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.GsonBuilder;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.model.neoform.NeoFormConversion;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "neoform", mixinStandardHelpOptions = true, description = "Convert a neoform config to a task graph.")
public class NeoForm implements Runnable {
    private final Main main;

    @CommandLine.Parameters(index = "0", description = "NeoForm zip file.")
    Path zip;

    @CommandLine.Parameters(index = "1", description = "Output config file.")
    Path target;

    @CommandLine.Option(names = "--distribution", description = "Distribution to extract.", required = true)
    String distribution;

    @CommandLine.Option(names = "--neoform-artifact-id", description = "Maven-style GAV used to reference the neoform zip within the generated file; default to using a file reference.")
    String selfReference = null;

    @CommandLine.Option(names = "--result", arity = "*", description = "Results, as <task>.<output>:<path> pairs, to include in the generated config.")
    List<String> results = List.of();

    NeoForm(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        try {
            Config config = NeoFormConversion.convert(zip, distribution, selfReference == null ? Value.file(zip) : Value.artifact(selfReference));
            var workItem = new WorkItem();
            for (var result : results) {
                var split = result.split(":");
                if (split.length != 2) {
                    throw new IllegalArgumentException("Invalid result format: " + result);
                }
                var taskOutput = split[0].split("\\.");
                if (taskOutput.length != 2) {
                    throw new IllegalArgumentException("Invalid task output format: " + split[0]);
                }
                workItem.results.put(new Output(taskOutput[0], taskOutput[1]), Path.of(split[1]));
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
