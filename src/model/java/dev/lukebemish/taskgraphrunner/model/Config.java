package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonAdapter(Config.Adapter.class)
public final class Config {
    public final List<TaskModel> tasks = new ArrayList<>();
    public final List<WorkItem> workItems = new ArrayList<>();
    public final Map<String, Value> parameters = new HashMap<>();

    static final class Adapter extends GsonAdapter<Config> {
        @Override
        public void write(JsonWriter out, Config value) throws IOException {
            out.beginObject();
            out.name("workItems");
            out.beginArray();
            for (WorkItem workItem : value.workItems) {
                GSON.toJson(workItem, WorkItem.class, out);
            }
            out.endArray();
            out.name("tasks");
            out.beginArray();
            for (TaskModel task : value.tasks) {
                GSON.toJson(task, TaskModel.class, out);
            }
            out.endArray();
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
        public Config read(JsonReader in) throws IOException {
            var config = new Config();
            in.beginObject();
            while (in.hasNext()) {
                var name = in.nextName();
                switch (name) {
                    case "parameters" -> {
                        in.beginObject();
                        while (in.hasNext()) {
                            var key = in.nextName();
                            Value value = GSON.fromJson(in, Value.class);
                            config.parameters.put(key, value);
                        }
                        in.endObject();
                    }
                    case "workItems" -> {
                        in.beginArray();
                        while (in.hasNext()) {
                            WorkItem workItem = GSON.fromJson(in, WorkItem.class);
                            config.workItems.add(workItem);
                        }
                        in.endArray();
                    }
                    case "tasks" -> {
                        in.beginArray();
                        while (in.hasNext()) {
                            TaskModel task = GSON.fromJson(in, TaskModel.class);
                            config.tasks.add(task);
                        }
                        in.endArray();
                    }
                    default -> {} // ignore unknown fields
                }
            }
            in.endObject();
            return config;
        }
    }
}
