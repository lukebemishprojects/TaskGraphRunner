package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public sealed interface Argument {
    record Value(Input input) implements Argument {
    }

    record FileInput(Input input, PathSensitivity pathSensitivity) implements Argument {
    }

    record FileOutput(String name, String extension) implements Argument {
    }

    record Classpath(Input input, boolean file) implements Argument {
    }

    record Zip(List<Input> inputs, PathSensitivity pathSensitivity) implements Argument {
    }

    default JsonElement toJson() {
        JsonObject json = new JsonObject();
        switch (this) {
            case Value value -> {
                json.addProperty("type", "value");
                json.add("input", value.input.toJson());
            }
            case Zip zip -> {
                json.addProperty("type", "zip");
                var inputs = new JsonArray();
                for (var input : zip.inputs) {
                    inputs.add(input.toJson());
                }
                json.add("inputs", inputs);
                json.addProperty("pathSensitivity", zip.pathSensitivity.name().toLowerCase(Locale.ROOT));
            }
            case Classpath classpath -> {
                json.addProperty("type", "classpath");
                json.add("input", classpath.input.toJson());
                json.addProperty("file", classpath.file);
            }
            case FileInput fileInput -> {
                json.addProperty("type", "fileInput");
                json.add("input", fileInput.input.toJson());
                json.addProperty("pathSensitivity", fileInput.pathSensitivity.name().toLowerCase(Locale.ROOT));
            }
            case FileOutput fileOutput -> {
                json.addProperty("type", "fileOutput");
                json.addProperty("name", fileOutput.name);
                json.addProperty("extension", fileOutput.extension);
            }
        }
        return json;
    }

    static Argument fromJson(JsonElement json) {
        if (json.isJsonPrimitive()) {
            return new Value(Input.fromJson(json));
        }
        var obj = json.getAsJsonObject();
        var type = obj.get("type").getAsString();
        return switch (type) {
            case "zip" -> {
                var inputs = obj.getAsJsonArray("inputs");
                var inputList = new ArrayList<Input>();
                for (var input : inputs) {
                    inputList.add(Input.fromJson(input));
                }
                var pathSensitivity = PathSensitivity.valueOf(obj.get("pathSensitivity").getAsString().toUpperCase(Locale.ROOT));
                yield  new Zip(inputList, pathSensitivity);
            }
            case "value" -> {
                var input = Input.fromJson(obj.get("input"));
                yield new Value(input);
            }
            case "classpath" -> {
                var input = Input.fromJson(obj.get("input"));
                var file = obj.get("file").getAsBoolean();
                yield new Classpath(input, file);
            }
            case "fileInput" -> {
                var input = Input.fromJson(obj.get("input"));
                var pathSensitivity = PathSensitivity.valueOf(obj.get("pathSensitivity").getAsString().toUpperCase(Locale.ROOT));
                yield new FileInput(input, pathSensitivity);
            }
            case "fileOutput" -> {
                var name = obj.get("name").getAsString();
                var extension = obj.get("extension").getAsString();
                yield new FileOutput(name, extension);
            }
            default -> throw new IllegalStateException("Unexpected argument type: " + type);
        };
    }
}
