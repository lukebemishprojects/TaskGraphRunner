package dev.lukebemish.taskgraphrunner.runtime.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class Tools {
    private Tools() {}

    public static final String JST;
    public static final String DIFF_PATCH;
    public static final String LINEMAPPER;
    public static final String LINEMAPPER_JST;
    public static final String ACCESSTRANSFORMERS;

    private static final Map<String, String> TOOLS;

    static {
        Properties properties = new Properties();
        Map<String, String> tools = new HashMap<>();
        try (var is = Tools.class.getResourceAsStream("/tools.properties")) {
            properties.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load tools.properties", e);
        }
        for (var entry : properties.entrySet()) {
            var key = entry.getKey().toString();
            var value = entry.getValue().toString();
            tools.put(key, value);
        }
        TOOLS = Map.copyOf(tools);
        JST = properties.getProperty("jst");
        DIFF_PATCH = properties.getProperty("diffpatch");
        LINEMAPPER = properties.getProperty("linemapper");
        LINEMAPPER_JST = properties.getProperty("linemapper-jst");
        ACCESSTRANSFORMERS = properties.getProperty("accesstransformers");
    }

    public static String tool(String tool) {
        return TOOLS.get(tool);
    }
}
