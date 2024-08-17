package dev.lukebemish.taskgraphmodel.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public record Output(String taskName, String name) {
    public JsonElement toJson() {
        return new JsonPrimitive(taskName+"."+name);
    }

    public static Output fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            String value = json.getAsString();
            String[] parts = value.split("\\.");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid output format `" + value + "`; should take the format `<task>.<output>`");
            }
            return new Output(parts[0], parts[1]);
        } else {
            throw new IllegalArgumentException("Not a string `"+json+"`");
        }
    }
}
