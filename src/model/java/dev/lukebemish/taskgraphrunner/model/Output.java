package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

@JsonAdapter(Output.Adapter.class)
public record Output(String taskName, String name) {
    public static final class Adapter extends GsonAdapter<Output> {
        @Override
        public void write(JsonWriter out, Output value) throws IOException {
            out.value(value.taskName() + "." + value.name());
        }

        @Override
        public Output read(JsonReader in) throws IOException {
            var value = in.nextString();
            var parts = value.split("\\.");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid output format, expected <task>.<output>: " + value);
            }
            return new Output(parts[0], parts[1]);
        }
    }
}
