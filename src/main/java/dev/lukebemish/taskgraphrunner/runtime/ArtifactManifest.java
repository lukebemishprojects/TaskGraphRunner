package dev.lukebemish.taskgraphrunner.runtime;

import dev.lukebemish.taskgraphrunner.runtime.util.DownloadUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.Tools;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public abstract sealed class ArtifactManifest {
    protected ArtifactManifest() {}

    protected abstract @Nullable Path tryFindArtifact(String notation);

    public Path findArtifact(String notation) {
        var artifact = tryFindArtifact(notation);
        if (artifact == null) {
            throw new IllegalArgumentException("No such artifact `"+notation+"`");
        }
        return artifact;
    }

    public String absolute(String line) {
        if (line.startsWith("file:")) {
            return "file:"+Path.of(line.substring("file:".length())).toAbsolutePath();
        } else if (line.startsWith("artifact:")) {
            return line;
        } else if (line.startsWith("tool:")) {
            if (Tools.tool(line.substring("tool:".length())) == null) {
                return line;
            } else {
                throw new IllegalArgumentException("Unknown tool: "+ line);
            }
        } else {
            throw new IllegalArgumentException("Unknown library notation: "+ line);
        }
    }

    public Path resolve(String line) {
        if (line.startsWith("file:")) {
            return Path.of(line.substring("file:".length()));
        } else if (line.startsWith("artifact:")) {
            var notation = line.substring("artifact:".length());
            return findArtifact(notation);
        } else if (line.startsWith("tool:")) {
            var tool = line.substring("tool:".length());
            var notation = Tools.tool(tool);
            if (notation == null) {
                throw new IllegalArgumentException("Unknown tool: "+ tool);
            }
            return findArtifact(notation);
        } else {
            throw new IllegalArgumentException("Unknown library notation: "+ line);
        }
    }

    public static ArtifactManifest fromPath(Path manifest) {
        return new PathArtifactManifest(manifest);
    }

    public static ArtifactManifest delegating(List<ArtifactManifest> delegates) {
        return new DelegatingArtifactManifest(delegates);
    }

    public static ArtifactManifest mavenDownload(Path mavenArtifactManifest, URI mavenUrl, Path targetDirectory) {
        return new MavenArtifactManifest(mavenArtifactManifest, mavenUrl, targetDirectory);
    }

    private static final class DelegatingArtifactManifest extends ArtifactManifest {
        private final List<ArtifactManifest> delegates;

        private DelegatingArtifactManifest(List<ArtifactManifest> delegates) {
            this.delegates = delegates;
        }

        @Override
        public Path tryFindArtifact(String notation) {
            for (var delegate : delegates) {
                var artifact = delegate.tryFindArtifact(notation);
                if (artifact != null) {
                    return artifact;
                }
            }
            return null;
        }
    }

    private static final class PathArtifactManifest extends ArtifactManifest {
        private final Map<String, Path> artifacts = new HashMap<>();

        public PathArtifactManifest(Path manifest) {
            try (var reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                for (var entry : properties.entrySet()) {
                    var key = entry.getKey().toString();
                    var value = entry.getValue().toString();
                    artifacts.put(key, Path.of(value));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public @Nullable Path tryFindArtifact(String notation) {
            return artifacts.get(notation);
        }
    }

    private synchronized static void appendArtifact(Path propertiesFile, String notation, Path path) throws IOException {
        var properties = new Properties();
        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        properties.setProperty(notation, path.toAbsolutePath().toString());
        try (var writer = Files.newBufferedWriter(propertiesFile, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
    }

    private static final class MavenArtifactManifest extends ArtifactManifest {
        private final Path mavenArtifactManifest;
        private final URI mavenUrl;
        private final Path targetDirectory;
        private final Map<String, Path> artifacts = new HashMap<>();

        public MavenArtifactManifest(Path mavenArtifactManifest, URI mavenUrl, Path targetDirectory) {
            this.mavenArtifactManifest = mavenArtifactManifest;
            this.mavenUrl = mavenUrl;
            this.targetDirectory = targetDirectory;
        }

        @Override
        protected @Nullable Path tryFindArtifact(String notation) {
            if (artifacts.containsKey(notation)) {
                return artifacts.get(notation);
            }
            var pattern = Pattern.compile("^(?<group>[^:@]+):(?<artifact>[^:@]+):(?<version>[^:@]+)(:(?<classifier>[^:@]+))?(@(?<extension>[^:@]+))?$");
            var matcher = pattern.matcher(notation);
            if (!matcher.matches()) {
                return null;
            }
            var group = matcher.group("group");
            var artifact = matcher.group("artifact");
            var version = matcher.group("version");
            var classifier = matcher.group("classifier");
            var extension = matcher.group("extension");
            if (extension == null) {
                extension = "jar";
            }
            var relative = group.replace('.', '/')+"/"+artifact+"/"+version+"/"+artifact+"-"+version+(classifier == null ? "" : "-"+classifier)+"."+extension;
            var target = targetDirectory.resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                var fullUrl = mavenUrl.resolve(relative);
                DownloadUtils.download(new DownloadUtils.Spec.Simple(fullUrl), target);
                artifacts.put(notation, target);
                appendArtifact(mavenArtifactManifest, notation, target);
                return target;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
