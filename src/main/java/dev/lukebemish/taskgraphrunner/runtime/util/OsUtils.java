package dev.lukebemish.taskgraphrunner.runtime.util;

import java.util.Locale;

public final class OsUtils {
    private OsUtils() {}

    enum Type {
        WINDOWS,
        LINUX,
        MAC
    }

    private static final Type TYPE;

    static {
        var name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.startsWith("linux")) {
            TYPE = Type.LINUX;
        } else if (name.startsWith("mac os x")) {
            TYPE = Type.MAC;
        } else if (name.startsWith("windows")) {
            TYPE = Type.WINDOWS;
        } else {
            TYPE = null;
        }
    }

    public static boolean isWindows() {
        return TYPE == Type.WINDOWS;
    }

    public static boolean isLinux() {
        return TYPE == Type.LINUX;
    }

    public static boolean isMac() {
        return TYPE == Type.MAC;
    }
}
