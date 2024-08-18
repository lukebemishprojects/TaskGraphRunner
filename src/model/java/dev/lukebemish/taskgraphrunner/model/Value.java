package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonAdapter(Value.Adapter.class)
public sealed interface Value {
    Object value();

    @JsonAdapter(Adapter.class)
    record NumberValue(Number value) implements Value {}

    @JsonAdapter(Adapter.class)
    record StringValue(String value) implements Value {}

    @JsonAdapter(Adapter.class)
    record BooleanValue(Boolean value) implements Value {}

    @JsonAdapter(Adapter.class)
    record ListValue(List<Value> value) implements Value {}

    @JsonAdapter(Adapter.class)
    record MapValue(Map<String, Value> value) implements Value {}

    static StringValue artifact(String notation) {
        return new StringValue("artifact:" + notation);
    }

    static StringValue file(Path path) {
        return new StringValue("file:" + path.toAbsolutePath());
    }

    class Adapter extends GsonAdapter<Value> {
        @Override
        public void write(JsonWriter out, Value value) throws IOException {
            switch (value) {
                case BooleanValue booleanValue -> out.value(booleanValue.value());
                case NumberValue numberValue -> out.value(numberValue.value());
                case StringValue stringValue -> out.value(stringValue.value());
                case ListValue listValue -> {
                    out.beginArray();
                    for (var v : listValue.value()) {
                        write(out, v);
                    }
                    out.endArray();
                }
                case MapValue mapValue -> {
                    out.beginObject();
                    for (var entry : mapValue.value().entrySet()) {
                        out.name(entry.getKey());
                        write(out, entry.getValue());
                    }
                    out.endObject();
                }
            }
        }

        @Override
        public Value read(JsonReader in) throws IOException {
            return switch (in.peek()) {
                case BOOLEAN -> new BooleanValue(in.nextBoolean());
                case NUMBER -> new NumberValue(GSON.getAdapter(JsonPrimitive.class).read(in).getAsNumber());
                case STRING -> new StringValue(in.nextString());
                case BEGIN_ARRAY -> {
                    in.beginArray();
                    List<Value> list = new ArrayList<>();
                    while (in.hasNext()) {
                        list.add(read(in));
                    }
                    in.endArray();
                    yield new ListValue(list);
                }
                case BEGIN_OBJECT -> {
                    in.beginObject();
                    Map<String, Value> map = new HashMap<>();
                    while (in.hasNext()) {
                        String key = in.nextName();
                        Value value = read(in);
                        map.put(key, value);
                    }
                    in.endObject();
                    yield new MapValue(map);
                }
                default -> throw new IllegalStateException("Unexpected token: " + in.peek());
            };
        }
    }
}
