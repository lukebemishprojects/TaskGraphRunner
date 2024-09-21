package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Invocation;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CommandLine.Command(name = "run", mixinStandardHelpOptions = true, description = "Run a task graph")
public class Run implements Runnable {
    @CommandLine.Parameters(index = "0", description = "Configuration file.")
    Path config;

    @CommandLine.Option(
        names = "--use-cache",
        description = "Use cached results.",
        negatable = true,
        fallbackValue = "true"
    )
    boolean useCache = true;

    @CommandLine.Option(names = "--work", arity = "*", description = "Additional work item to run.")
    List<Path> workItems = List.of();

    @CommandLine.Option(
        names = "--refresh-cached-assets",
        negatable = true,
        fallbackValue = "false"
    )
    boolean refreshCachedAssets = false;

    @CommandLine.Option(
        names = "--use-launcher-asset-root",
        negatable = true,
        fallbackValue = "true"
    )
    boolean useLauncherAssetRoot = true;

    @CommandLine.Option(
        names = "--launcher-dir",
        arity = "*",
        description = "Specifies one or more Minecraft launcher installation directories to reuse assets from."
    )
    List<Path> launcherDirs = new ArrayList<>();

    private final Main main;

    Run(Main main) {
        this.main = main;
    }

    // Sourced from https://github.com/neoforged/NeoFormRuntime/blob/8b1f4a5ee3c76925dcd7fb23cb15ba98496d155f/src/main/java/net/neoforged/neoform/runtime/cache/LauncherInstallations.java#L26-L44
    // ${appdata} is replaced with the AppData folder, ${home} is replaced with the user's home directory, and ${localappdata} is replaced with the LocalAppData folder.
    private static final String[] LAUNCHER_DIR_CANDIDATES = {
        "${appdata}/.minecraft/", // Windows, default launcher
        "${home}/.minecraft/", // linux, default launcher
        "${home}/Library/Application Support/minecraft/", // macOS, default launcher
        "${home}/curseforge/minecraft/Install/", // Windows, Curseforge Client
        "${home}/com.modrinth.theseus/meta/", // Windows, Modrinth App
        "${localappdata}/.ftba/bin/", // Windows, FTB App
        "${home}/.ftba/bin/", // linux, FTB App
        "${home}/Library/Application Support/.ftba/bin/", // macos, FTB App
        "${home}/.local/share/PrismLauncher/", // linux, PrismLauncher
        "${home}/.local/share/multimc/", // linux, MultiMC
        "${home}/Library/Application Support/PrismLauncher/", // macOS, PrismLauncher
        "${appdata}/PrismLauncher/", // Windows, PrismLauncher
        "${home}/scoop/persist/multimc/", // Windows, MultiMC via Scoop
    };

    private static List<Path> launcherInstallationPaths() {
        var appData = System.getenv("APPDATA");
        var home = System.getProperty("user.home");
        var localAppData = System.getenv("LOCALAPPDATA");
        return Arrays.stream(LAUNCHER_DIR_CANDIDATES)
            .filter(s -> !s.contains("${appdata}") || appData != null)
            .filter(s -> !s.contains("${home}") || home != null)
            .filter(s -> !s.contains("${localappdata}") || localAppData != null)
            .map(s -> s
                .replace("${appdata}", appData)
                .replace("${home}", home)
                .replace("${localappdata}", localAppData)
            )
            .map(Paths::get)
            .toList();
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
                var assetsOptions = Context.AssetDownloadOptions.builder()
                    .assetRoot(main.cacheDir.resolve("assets"))
                    .redownloadAssets(refreshCachedAssets);
                var launcherDirs = new ArrayList<Path>();
                if (useLauncherAssetRoot) {
                    launcherDirs.addAll(this.launcherDirs);
                }
                assetsOptions.potentialLauncherRoots(launcherDirs);
                Invocation invocation = new Invocation(main.cacheDir, assetsOptions.build(), useCache);
                invocation.artifactManifest(main.makeManifest());
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
