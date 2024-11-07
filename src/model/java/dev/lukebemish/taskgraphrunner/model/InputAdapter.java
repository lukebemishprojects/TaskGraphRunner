package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class InputAdapter extends GsonAdapter<Input> {

    @Override
    public void write(JsonWriter out, Input value) throws IOException {
        switch (value) {
            case Input.DirectInput directInput -> {
                if (directInput.value() instanceof Value.StringValue stringValue) {
                    out.value("direct." + stringValue.value());
                } else {
                    out.beginObject();
                    out.name("type");
                    out.value("direct");
                    out.name("value");
                    GSON.getAdapter(Value.class).write(out, directInput.value());
                    out.endObject();
                }
            }
            case Input.ParameterInput parameterInput -> out.value("parameter." + parameterInput.parameter());
            case Input.TaskInput taskInput ->
                out.value("task." + taskInput.output().taskName() + "." + taskInput.output().name());
            case Input.ListInput listInput -> {
                if (listInput.listOrdering() == ListOrdering.ORIGINAL) {
                    out.beginArray();
                    for (var i : listInput.inputs()) {
                        write(out, i);
                    }
                    out.endArray();
                } else {
                    out.beginObject();
                    out.name("type");
                    out.value("list");
                    out.name("value");
                    out.beginArray();
                    for (var i : listInput.inputs()) {
                        write(out, i);
                    }
                    out.endArray();
                    out.name("listOrdering");
                    GSON.getAdapter(ListOrdering.class).write(out, listInput.listOrdering());
                    out.endObject();
                }
            }
        }
    }

    @Override
    public Input read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.STRING) {
            var string = in.nextString();
            var index = string.indexOf('.');
            if (index == -1) {
                throw new IllegalArgumentException("Invalid input format, expected <type>.<value>: " + string);
            }
            var prefix = string.substring(0, index);
            return switch (prefix) {
                case "direct" ->
                    new Input.DirectInput(new Value.StringValue(string.substring(string.indexOf('.') + 1)));
                case "parameter" -> new Input.ParameterInput(string.substring(string.indexOf('.') + 1));
                case "task" -> {
                    var parts = string.substring(string.indexOf('.') + 1).split("\\.");
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("Invalid output format, expected <task>.<output>: " + string);
                    }
                    yield new Input.TaskInput(new Output(parts[0], parts[1]));
                }
                default -> throw new IllegalArgumentException("Invalid input type: " + prefix);
            };
        } else if (in.peek() == JsonToken.BEGIN_ARRAY) {
            List<Input> inputs = new ArrayList<>();
            in.beginArray();
            while (in.peek() != JsonToken.END_ARRAY) {
                inputs.add(read(in));
            }
            in.endArray();
            return new Input.ListInput(inputs);
        }
        JsonObject object = GSON.getAdapter(JsonElement.class).read(in).getAsJsonObject();
        var type = object.get("type").getAsString();
        var value = object.get("value");
        return switch (type) {
            case "direct" -> new Input.DirectInput(GSON.fromJson(value, Value.class));
            case "parameter" -> new Input.ParameterInput(value.getAsString());
            case "task" -> {
                var output = GSON.fromJson(value, Output.class);
                yield new Input.TaskInput(output);
            }
            case "list" -> {
                List<Input> inputs = new ArrayList<>();
                for (var element : value.getAsJsonArray()) {
                    inputs.add(GSON.fromJson(element, Input.class));
                }
                if (!object.has("listOrdering")) {
                    yield new Input.ListInput(inputs);
                }
                var listOrdering = GSON.fromJson(object.get("listOrdering"), ListOrdering.class);
                yield new Input.ListInput(inputs, listOrdering);
            }
            default -> throw new IllegalArgumentException("Invalid input type: " + type);
        };
    }
}
