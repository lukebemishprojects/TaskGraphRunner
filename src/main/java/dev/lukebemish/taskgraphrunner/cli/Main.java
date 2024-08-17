package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.taskgraphrunner.runtime.util.OsUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.nio.file.Paths;

@CommandLine.Command(name = "taskgraphrunner", mixinStandardHelpOptions = true)
public class Main {
    @CommandLine.Option(names = "--cache-dir", description = "Where caches should be stored.")
    Path cacheDir = defaultCacheDirectory();

    public static void main(String[] args) {
        var main = new Main();
        new CommandLine(main)
            .addSubcommand("run", new Run(main))
            .addSubcommand("clean", new Clean(main))
            .execute(args);
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
