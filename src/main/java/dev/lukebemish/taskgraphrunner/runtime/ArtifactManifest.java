package dev.lukebemish.taskgraphrunner.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ArtifactManifest {
    private final Map<String, Path> artifacts = new HashMap<>();

    public ArtifactManifest() {}

    public void artifactManifest(Path manifest) {
        try (var reader = Files.newBufferedReader(manifest, StandardCharsets.ISO_8859_1)) {
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

    public Path findArtifact(String notation) {
        var artifact = artifacts.get(notation);
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
        } else {
            throw new IllegalArgumentException("Unknown library notation: "+ line);
        }
    }
}
