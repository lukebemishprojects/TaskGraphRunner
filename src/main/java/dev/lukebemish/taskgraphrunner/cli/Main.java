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
public class Main {
    @CommandLine.Option(names = "--cache-dir", description = "Where caches should be stored.")
    Path cacheDir = defaultCacheDirectory();

    @CommandLine.ArgGroup(exclusive = false, multiplicity = "*", heading = "Artifact manifests%n")
    List<ArtifactManifestOption> artifactManifests = new ArrayList<>();

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

    protected ArtifactManifest makeManifest() {
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

    public static void main(String[] args) {
        var main = new Main();
        new CommandLine(main)
            .addSubcommand("run", new Run(main))
            .addSubcommand("clean", new Clean(main))
            .addSubcommand("neoform", new NeoForm(main))
            .addSubcommand("vanilla", new Vanilla(main))
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
