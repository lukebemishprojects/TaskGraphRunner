package dev.lukebemish.taskgraphmodel.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public enum Distribution {
    CLIENT,
    SERVER,
    JOINED;

    public JsonElement toJson() {
        return new JsonPrimitive(this.name().toLowerCase(Locale.ROOT));
    }

    public static Distribution fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            return Distribution.valueOf(json.getAsString().toUpperCase(Locale.ROOT));
        } else {
            throw new IllegalArgumentException("Not a string `"+json+"`");
        }
    }
}
