package dev.lukebemish.taskgraphmodel.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public sealed interface Input {
    record TaskInput(String task, String output) implements Input {
        @Override
        public JsonElement toJson() {
            return new JsonPrimitive("task."+task+"."+output);
        }
    }
    record ParameterInput(String parameter) implements Input {
        @Override
        public JsonElement toJson() {
            return new JsonPrimitive("parameter."+parameter);
        }
    }

    JsonElement toJson();

    static Input fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            String value = json.getAsString();
            if (value.startsWith("task.")) {
                String[] parts = value.split("\\.");
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid task input `" + value + "`; should take the format `task.<task>.<output>`");
                }
                return new TaskInput(parts[1], parts[2]);
            } else if (value.startsWith("parameter.")) {
                return new ParameterInput(value.substring(10));
            } else {
                throw new IllegalArgumentException("Invalid input format for `" + value + "`");
            }
        } else {
            throw new IllegalArgumentException("Not a string `"+json+"`");
        }
    }
}
