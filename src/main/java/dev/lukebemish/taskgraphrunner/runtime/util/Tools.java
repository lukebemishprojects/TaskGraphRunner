package dev.lukebemish.taskgraphrunner.runtime.util;

import java.io.IOException;
import java.util.Properties;

public final class Tools {
    private Tools() {}

    public static final String JST;
    public static final String DIFF_PATCH;

    static {
        Properties properties = new Properties();
        try (var is = Tools.class.getResourceAsStream("/tools.properties")) {
            properties.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load tools.properties", e);
        }
        JST = properties.getProperty("jst");
        DIFF_PATCH = properties.getProperty("diffpatch");
    }
}
