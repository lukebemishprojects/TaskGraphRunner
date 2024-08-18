package dev.lukebemish.taskgraphrunner.runtime.util;

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
}
