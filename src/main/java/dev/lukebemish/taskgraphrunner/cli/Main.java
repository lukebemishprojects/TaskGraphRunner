package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.runtime.Invocation;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.util.OsUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@CommandLine.Command(name = "taskgraphrunner", mixinStandardHelpOptions = true)
public class Main implements Runnable {
    @CommandLine.Parameters(index = "0", description = "Configuration file.")
    Path config;

    @CommandLine.Option(names = "--artifact-manifest", description = "Artifact manifest files.", arity = "*")
    List<Path> artifactManifests = List.of();

    @CommandLine.Option(names = "--cache-dir", description = "Where caches should be stored.")
    Path cacheDir = defaultCacheDirectory();

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }

    private static final Gson GSON = new Gson();

    @Override
    public void run() {
        JsonObject json;
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            json = GSON.fromJson(reader, JsonObject.class);
            var config = Config.fromJson(json);
            for (var workItem : config.workItems()) {
                Invocation invocation = new Invocation(cacheDir);
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

    private static Path defaultCacheDirectory() {
        var userHomeDir = Paths.get(System.getProperty("user.home"));

        if (OsUtils.isLinux()) {
            var xdgCacheHome = System.getenv("XDG_CACHE_DIR");
            if (xdgCacheHome != null && xdgCacheHome.startsWith("/")) {
                return Paths.get(xdgCacheHome).resolve("taskgraphrunner");
            } else {
                return userHomeDir.resolve(".cache/taskgraphrunner");
            }
        }
        return userHomeDir.resolve(".taskgraphrunner");
    }
}
