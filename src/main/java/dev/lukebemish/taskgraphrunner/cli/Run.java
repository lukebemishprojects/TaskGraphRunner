package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Invocation;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CommandLine.Command(name = "run", mixinStandardHelpOptions = true, description = "Run a task graph")
public class Run implements Runnable {
    @CommandLine.Parameters(index = "0", description = "Configuration file.")
    Path config;

    @CommandLine.Option(names = "--skip-cache", description = "Avoids using cached results.")
    boolean skipCache = false;

    @CommandLine.Option(names = "--work", arity = "*", description = "Additional work item to run.")
    List<Path> workItems = List.of();

    private final Main main;

    Run(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            var config = JsonUtils.GSON.fromJson(reader, Config.class);
            List<WorkItem> workItems = new ArrayList<>(config.workItems);
            for (var workItemPath : this.workItems) {
                try (var workItemReader = Files.newBufferedReader(workItemPath, StandardCharsets.UTF_8)) {
                    workItems.add(JsonUtils.GSON.fromJson(workItemReader, WorkItem.class));
                }
            }
            for (var workItem : workItems) {
                var parameters = new HashMap<String, Value>();
                parameters.putAll(config.parameters);
                parameters.putAll(workItem.parameters);
                workItem.parameters.clear();
                workItem.parameters.putAll(parameters);
                Invocation invocation = new Invocation(main.cacheDir, !skipCache);
                for (var manifest : main.artifactManifests) {
                    invocation.artifactManifest(manifest);
                }
                for (var model : config.tasks) {
                    var task = Task.task(model, workItem, invocation);
                    invocation.addTask(task);
                }
                Map<Output, Path> results = new HashMap<>();
                for (var entry : workItem.results.entrySet()) {
                    results.put(switch (entry.getKey()) {
                        case WorkItem.Target.AliasTarget aliasTarget -> config.aliases.get(aliasTarget.alias());
                        case WorkItem.Target.OutputTarget outputTarget -> outputTarget.output();
                    }, entry.getValue());
                }
                invocation.execute(results);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
