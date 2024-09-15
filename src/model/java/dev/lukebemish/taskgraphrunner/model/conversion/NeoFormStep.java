package dev.lukebemish.taskgraphrunner.model.conversion;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

@JsonAdapter(NeoFormStep.Adapter.class)
record NeoFormStep(
    String type,
    String name,
    Map<String, String> values
) {
    NeoFormStep {
        if (name == null) {
            name = type;
        }
    }

    static final class Adapter extends TypeAdapter<NeoFormStep> {

        @Override
        public void write(JsonWriter out, NeoFormStep value) throws IOException {
            out.beginObject();
            out.name("type").value(value.type());
            if (!value.name().equals(value.type())) {
                out.name("name").value(value.name());
            }
            value.values().forEach((k, v) -> {
                try {
                    out.name(k).value(v);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            out.endObject();
        }

        @Override
        public NeoFormStep read(JsonReader in) throws IOException {
            String name = null;
            String type = null;
            Map<String, String> values = new HashMap<>();
            in.beginObject();
            while (in.hasNext()) {
                var nextName = in.nextName();
                switch (nextName) {
                    case "type" -> type = in.nextString();
                    case "name" -> name = in.nextString();
                    default -> values.put(nextName, in.nextString());
                }
            }
            in.endObject();
            return new NeoFormStep(type, name, values);
        }
    }
}
