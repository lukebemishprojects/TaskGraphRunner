package dev.lukebemish.taskgraphrunner.runtime.manifest.version;

import com.google.gson.annotations.SerializedName;
import dev.lukebemish.taskgraphrunner.runtime.util.OsUtils;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record Rule(Action action, Map<String, Boolean> features, Rule.@Nullable OsDetails os) {
    public Rule {
        Objects.requireNonNull(action);
        if (features == null) {
            features = Map.of();
        }
    }

    public enum Action {
        @SerializedName("allow") ALLOW,
        @SerializedName("disallow") DISALLOW
    }

    public boolean matches() {
        return features.isEmpty() && (os == null || os.matches());
    }

    public record OsDetails(@Nullable String name, @Nullable String version, @Nullable String arch) {
        public boolean correctType() {
            return switch (name) {
                case "linux" -> OsUtils.isLinux();
                case "osx" -> OsUtils.isMac();
                case "windows" -> OsUtils.isWindows();
                case null -> true;
                default -> false;
            };
        }

        public boolean correctVersion() {
            return version == null || Pattern.compile(version).matcher(System.getProperty("os.version")).find();
        }

        public boolean correctArch() {
            return arch == null || Pattern.compile(arch).matcher(System.getProperty("os.arch")).find();
        }

        public boolean matches() {
            return correctType() && correctVersion() && correctArch();
        }
    }
}
