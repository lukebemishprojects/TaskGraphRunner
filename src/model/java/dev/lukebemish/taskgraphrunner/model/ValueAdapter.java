package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ValueAdapter extends GsonAdapter<Value> {
    private static final String ORDER_BY_CONTENTS = "__taskgraphrunner.listOrdering."+ ListOrdering.CONTENTS.name()+"__";

    @Override
    public void write(JsonWriter out, Value value) throws IOException {
        switch (value) {
            case Value.BooleanValue booleanValue -> out.value(booleanValue.value());
            case Value.NumberValue numberValue -> out.value(numberValue.value());
            case Value.DirectStringValue stringValue -> out.value(stringValue.value());
            case Value.ListValue listValue -> {
                out.beginArray();
                if (listValue.listOrdering() == ListOrdering.CONTENTS) {
                    out.value(ORDER_BY_CONTENTS);
                }
                for (var v : listValue.value()) {
                    write(out, v);
                }
                out.endArray();
            }
            case Value.MapValue mapValue -> {
                out.beginObject();
                out.name("type").value("map");
                out.name("value");
                out.beginObject();
                for (var entry : mapValue.value().entrySet()) {
                    out.name(entry.getKey());
                    write(out, entry.getValue());
                }
                out.endObject();
                out.endObject();
            }
            case Value.SystemPropertyValue systemPropertyValue -> {
                out.beginObject();
                out.name("type").value("systemProperty");
                out.name("property").value(systemPropertyValue.property());
                out.name("defaultValue").value(systemPropertyValue.defaultValue());
                out.endObject();
            }
        }
    }

    @Override
    public Value read(JsonReader in) throws IOException {
        return switch (in.peek()) {
            case BOOLEAN -> new Value.BooleanValue(in.nextBoolean());
            case NUMBER -> new Value.NumberValue(GSON.getAdapter(JsonPrimitive.class).read(in).getAsNumber());
            case STRING -> new Value.DirectStringValue(in.nextString());
            case BEGIN_ARRAY -> {
                in.beginArray();
                List<Value> list = new ArrayList<>();
                ListOrdering listOrdering = ListOrdering.ORIGINAL;
                if (in.hasNext()) {
                    var value = read(in);
                    if (value instanceof Value.DirectStringValue stringValue && stringValue.value().equals(ORDER_BY_CONTENTS)) {
                        listOrdering = ListOrdering.CONTENTS;
                    } else {
                        list.add(value);
                    }
                }
                while (in.hasNext()) {
                    list.add(read(in));
                }
                in.endArray();
                yield new Value.ListValue(list, listOrdering);
            }
            case BEGIN_OBJECT -> {
                JsonObject object = GSON.getAdapter(JsonElement.class).read(in).getAsJsonObject();
                var type = object.get("type").getAsString();
                var value = object.get("value");
                yield switch (type) {
                    case "map" -> {
                        Map<String, Value> map = new HashMap<>();
                        for (var entry : value.getAsJsonObject().entrySet()) {
                            map.put(entry.getKey(), GSON.getAdapter(Value.class).fromJsonTree(entry.getValue()));
                        }
                        yield new Value.MapValue(map);
                    }
                    case "systemProperty" -> {
                        var property = object.get("property").getAsString();
                        var defaultValue = object.get("defaultValue").getAsString();
                        yield new Value.SystemPropertyValue(property, defaultValue);
                    }
                    case "list" -> {
                        List<Value> list = new ArrayList<>();
                        for (var v : value.getAsJsonArray()) {
                            list.add(GSON.getAdapter(Value.class).fromJsonTree(v));
                        }
                        var listOrdering = GSON.getAdapter(ListOrdering.class).fromJsonTree(object.get("listOrdering"));
                        yield new Value.ListValue(list, listOrdering);
                    }
                    case "string" -> new Value.DirectStringValue(value.getAsString());
                    case "number" -> new Value.NumberValue(value.getAsNumber());
                    case "boolean" -> new Value.BooleanValue(value.getAsBoolean());
                    default -> throw new IllegalStateException("Unexpected type: " + type);
                };
            }
            default -> throw new IllegalStateException("Unexpected token: " + in.peek());
        };
    }
}
