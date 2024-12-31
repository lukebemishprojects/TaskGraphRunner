package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class InputValueAdapter extends GsonAdapter<InputValue> {
    @Override
    public void write(JsonWriter out, InputValue value) throws IOException {
        switch (value) {
            case InputValue.DirectInput directInput -> {
                if (directInput.value() instanceof Value.DirectStringValue stringValue) {
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
            case InputValue.ParameterInput parameterInput -> out.value("parameter." + parameterInput.parameter());
            case InputValue.ListInput listInput -> {
                out.beginArray();
                for (var i : listInput.inputs()) {
                    write(out, i);
                }
                out.endArray();
            }
        }
    }

    @Override
    public InputValue read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.STRING) {
            var string = in.nextString();
            var index = string.indexOf('.');
            if (index == -1) {
                throw new IllegalArgumentException("Invalid input format, expected <type>.<value>: " + string);
            }
            var prefix = string.substring(0, index);
            return switch (prefix) {
                case "direct" ->
                    new InputValue.DirectInput(new Value.DirectStringValue(string.substring(string.indexOf('.') + 1)));
                case "parameter" -> new InputValue.ParameterInput(string.substring(string.indexOf('.') + 1));
                default -> throw new IllegalArgumentException("Invalid input type: " + prefix);
            };
        } else if (in.peek() == JsonToken.BEGIN_ARRAY) {
            List<InputValue> inputs = new ArrayList<>();
            in.beginArray();
            while (in.peek() != JsonToken.END_ARRAY) {
                inputs.add(read(in));
            }
            in.endArray();
            return new InputValue.ListInput(inputs);
        }
        JsonObject object = GSON.getAdapter(JsonElement.class).read(in).getAsJsonObject();
        var type = object.get("type").getAsString();
        var value = object.get("value");
        return switch (type) {
            case "direct" -> new InputValue.DirectInput(GSON.fromJson(value, Value.class));
            case "parameter" -> new InputValue.ParameterInput(value.getAsString());
            case "list" -> {
                List<InputValue> inputs = new ArrayList<>();
                for (var element : value.getAsJsonArray()) {
                    inputs.add(GSON.fromJson(element, InputValue.class));
                }
                yield new InputValue.ListInput(inputs);
            }
            default -> throw new IllegalArgumentException("Invalid input type: " + type);
        };
    }
}
