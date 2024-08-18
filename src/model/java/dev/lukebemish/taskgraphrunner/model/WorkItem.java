package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@JsonAdapter(WorkItem.Adapter.class)
public final class WorkItem {
    public final Map<Output, Path> results = new HashMap<>();
    public final Map<String, Value> parameters = new HashMap<>();

    static final class Adapter extends GsonAdapter<WorkItem> {
        @Override
        public void write(JsonWriter out, WorkItem value) throws IOException {
            out.beginObject();
            out.name("results");
            out.beginObject();
            for (Map.Entry<Output, Path> entry : value.results.entrySet()) {
                out.name(entry.getKey().taskName()+"."+entry.getKey().name());
                out.value(entry.getValue().toAbsolutePath().toString());
            }
            out.endObject();
            out.name("parameters");
            out.beginObject();
            for (Map.Entry<String, Value> entry : value.parameters.entrySet()) {
                out.name(entry.getKey());
                GSON.toJson(entry.getValue(), Value.class, out);
            }
            out.endObject();
            out.endObject();
        }

        @Override
        public WorkItem read(JsonReader in) throws IOException {
            var workItem = new WorkItem();
            in.beginObject();
            while (in.hasNext()) {
                var name = in.nextName();
                switch (name) {
                    case "results" -> {
                        in.beginObject();
                        while (in.hasNext()) {
                            var key = in.nextName();
                            var value = in.nextString();
                            var parts = key.split("\\.");
                            if (parts.length != 2) {
                                throw new IllegalArgumentException("Invalid output format, expected <task>.<output>: " + key);
                            }
                            workItem.results.put(new Output(parts[0], parts[1]), Path.of(value));
                        }
                        in.endObject();
                    }
                    case "parameters" -> {
                        in.beginObject();
                        while (in.hasNext()) {
                            var key = in.nextName();
                            var value = GSON.getAdapter(Value.class).read(in);
                            workItem.parameters.put(key, value);
                        }
                        in.endObject();
                    }
                    default -> {} // ignore unknown fields
                }
            }
            in.endObject();
            return workItem;
        }
    }
}
