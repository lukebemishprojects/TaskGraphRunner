package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

@JsonAdapter(Input.Adapter.class)
public sealed interface Input {
    @JsonAdapter(Adapter.class)
    record TaskInput(Output output) implements Input {}

    @JsonAdapter(Adapter.class)
    record ParameterInput(String parameter) implements Input {}

    @JsonAdapter(Adapter.class)
    record DirectInput(Value value) implements Input {}

    final class Adapter extends GsonAdapter<Input> {

        @Override
        public void write(JsonWriter out, Input value) throws IOException {
            switch (value) {
                case DirectInput directInput -> {
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
                case ParameterInput parameterInput -> out.value("parameter."+parameterInput.parameter());
                case TaskInput taskInput -> out.value("task."+taskInput.output().taskName()+"."+taskInput.output().name());
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
                    case "direct" -> new DirectInput(new Value.StringValue(string.substring(string.indexOf('.')+1)));
                    case "parameter" -> new ParameterInput(string.substring(string.indexOf('.')+1));
                    case "task" -> {
                        var parts = string.substring(string.indexOf('.')+1).split("\\.");
                        if (parts.length != 2) {
                            throw new IllegalArgumentException("Invalid output format, expected <task>.<output>: " + string);
                        }
                        yield new TaskInput(new Output(parts[0], parts[1]));
                    }
                    default -> throw new IllegalArgumentException("Invalid input type: " + prefix);
                };
            }
            JsonObject object = GSON.getAdapter(JsonElement.class).read(in).getAsJsonObject();
            var type = object.get("type").getAsString();
            var value = object.get("value");
            return switch (type) {
                case "direct" -> new DirectInput(GSON.fromJson(value, Value.class));
                case "parameter" -> new ParameterInput(value.getAsString());
                case "task" -> {
                    var output = GSON.fromJson(value, Output.class);
                    yield new TaskInput(output);
                }
                default -> throw new IllegalArgumentException("Invalid input type: " + type);
            };
        }
    }
}
