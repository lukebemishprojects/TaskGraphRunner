package dev.lukebemish.taskgraphrunner.runtime.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;

public final class FileUtils {
    private FileUtils() {}

    public static String escapeForArgument(String argument) {
        if (argument.contains("\"") || argument.contains(" ") || argument.contains("'")) {
            argument = argument.replace("\\", "\\\\");
            argument = argument.replace("\"", "\\\"");
            return "\"" + argument + "\"";
        }
        return argument;
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    var next = iterator.next();
                    deleteRecursively(next);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    public static void setLastAccessedTime(Path path, FileTime now) throws IOException {
        Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(null, now, null);
    }
}
