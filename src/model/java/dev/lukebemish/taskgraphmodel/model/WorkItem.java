package dev.lukebemish.taskgraphmodel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record WorkItem(Map<Output, Path> results, Map<String, JsonElement> parameters) {
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        JsonObject resultsObject = new JsonObject();
        for (Map.Entry<Output, Path> entry : results.entrySet()) {
            resultsObject.addProperty(entry.getKey().toJson().getAsString(), entry.getValue().toAbsolutePath().toString());
        }
        JsonObject parametersObject = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
            parametersObject.add(entry.getKey(), entry.getValue());
        }
        json.add("results", resultsObject);
        json.add("parameters", parametersObject);
        return json;
    }

    public static WorkItem fromJson(JsonElement json) {
        if (json.isJsonObject()) {
            JsonObject jsonObject = json.getAsJsonObject();
            JsonObject resultsObject = jsonObject.getAsJsonObject("results");
            Map<Output, Path> results = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : resultsObject.entrySet()) {
                Output output = Output.fromJson(new JsonPrimitive(entry.getKey()));
                Path path = Path.of(entry.getValue().getAsString());
                results.put(output, path);
            }
            Map<String, JsonElement> parameters = new HashMap<>();
            JsonObject parametersObject = jsonObject.getAsJsonObject("parameters");
            for (Map.Entry<String, JsonElement> entry : parametersObject.entrySet()) {
                parameters.put(entry.getKey(), entry.getValue());
            }
            return new WorkItem(Map.copyOf(results), Map.copyOf(parameters));
        } else {
            throw new IllegalArgumentException("Not an object `"+json+"`");
        }
    }
}
