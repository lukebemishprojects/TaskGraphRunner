package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.taskgraphrunner.runtime.ArtifactManifest;
import dev.lukebemish.taskgraphrunner.runtime.util.OsUtils;
import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@CommandLine.Command(name = "taskgraphrunner", mixinStandardHelpOptions = true)
public class Main implements Runnable {
    @CommandLine.Option(names = "--cache-dir", description = "Where caches should be stored.")
    Path cacheDir = defaultCacheDirectory();

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "*", heading = "Artifact manifests%n")
    List<ArtifactManifestOption> artifactManifests = new ArrayList<>();

    public Main(String[] args) {
        this.args = args;
    }

    @Override
    public void run() {
        System.err.println("No subcommand specified.");
        new CommandLine(this).usage(System.err);
    }

    static class ArtifactManifestOption {
        @CommandLine.Option(names = "--artifact-manifest", description = "Artifact manifest files.", required = true)
        Path artifactManifest;
        @CommandLine.ArgGroup(exclusive = false, heading = "Maven-downloading artifact manifest%n")
        MavenArtifactManifest maven;
    }

    static class MavenArtifactManifest {
        @CommandLine.Option(names = "--maven-url", description = "Maven URL.", required = true)
        URI mavenUrl;
        @CommandLine.Option(names = "--maven-download-directory", description = "Maven repository.", required = true)
        Path targetDirectory;
    }

    ArtifactManifest makeManifest() {
        var manifests = new ArrayList<ArtifactManifest>();
        ArtifactManifest manifest = ArtifactManifest.delegating(manifests);
        for (var m : artifactManifests) {
            if (m.maven != null) {
                manifests.add(ArtifactManifest.mavenDownload(m.artifactManifest, m.maven.mavenUrl, m.maven.targetDirectory));
            } else {
                manifests.add(ArtifactManifest.fromPath(m.artifactManifest));
            }
        }
        return manifest;
    }

    final String[] args;

    public static void main(String[] args) {
        try {
            var main = new Main(args);
            System.exit(main.execute(args));
        } catch (Throwable t) {
            logException(t);
            System.exit(1);
        }
    }

    int execute(String[] args) {
        return new CommandLine(this)
            .addSubcommand("run", new Run(this))
            .addSubcommand("clean", new Clean(this))
            .addSubcommand("neoform", new NeoForm(this))
            .addSubcommand("vanilla", new Vanilla(this))
            .addSubcommand("daemon", new Daemon(this))
            .addSubcommand("mark", new Mark(this))
            .addSubcommand("mermaid", new Mermaid(this))
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

    // Primarily for the daemon, but may be useful otherwise too
    private static final boolean STACKTRACE = !Boolean.getBoolean("dev.lukebemish.taskgraphrunner.hidestacktrace");

    static void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            logException(t);
        }
    }

    static void logException(Throwable t) {
        if (STACKTRACE) {
            t.printStackTrace(System.err);
        } else {
            System.err.println(t);
        }
    }
}
