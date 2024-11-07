package dev.lukebemish.taskgraphrunner.model;

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
            case Value.StringValue stringValue -> out.value(stringValue.value());
            case Value.ListValue listValue -> {
                out.beginArray();
                if (listValue.listOrdering() == ListOrdering.CONTENTS) {
                    out.value(ORDER_BY_CONTENTS +listValue.listOrdering().name());
                }
                for (var v : listValue.value()) {
                    write(out, v);
                }
                out.endArray();
            }
            case Value.MapValue mapValue -> {
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
            case BOOLEAN -> new Value.BooleanValue(in.nextBoolean());
            case NUMBER -> new Value.NumberValue(GSON.getAdapter(JsonPrimitive.class).read(in).getAsNumber());
            case STRING -> new Value.StringValue(in.nextString());
            case BEGIN_ARRAY -> {
                in.beginArray();
                List<Value> list = new ArrayList<>();
                ListOrdering listOrdering = ListOrdering.ORIGINAL;
                if (in.hasNext()) {
                    var value = read(in);
                    if (value instanceof Value.StringValue stringValue && stringValue.value().equals(ORDER_BY_CONTENTS)) {
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
                in.beginObject();
                Map<String, Value> map = new HashMap<>();
                while (in.hasNext()) {
                    String key = in.nextName();
                    Value value = read(in);
                    map.put(key, value);
                }
                in.endObject();
                yield new Value.MapValue(map);
            }
            default -> throw new IllegalStateException("Unexpected token: " + in.peek());
        };
    }
}
