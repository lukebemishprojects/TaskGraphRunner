package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.runtime.Invocation;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "run", mixinStandardHelpOptions = true, description = "Run a task graph")
public class Run implements Runnable {
    @CommandLine.Parameters(index = "0", description = "Configuration file.")
    Path config;

    @CommandLine.Option(names = "--artifact-manifest", description = "Artifact manifest files.", arity = "*")
    List<Path> artifactManifests = List.of();

    private final Main main;

    public Run(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        JsonObject json;
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            json = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            var config = Config.fromJson(json);
            for (var workItem : config.workItems()) {
                Invocation invocation = new Invocation(main.cacheDir);
                for (var manifest : artifactManifests) {
                    invocation.artifactManifest(manifest);
                }
                for (var model : config.tasks()) {
                    var task = Task.task(model, workItem, invocation);
                    invocation.addTask(task);
                }
                invocation.execute(workItem.results());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
