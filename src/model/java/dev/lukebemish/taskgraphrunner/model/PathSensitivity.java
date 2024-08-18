package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;

@JsonAdapter(PathSensitivity.Adapter.class)
public enum PathSensitivity {
    ABSOLUTE,
    NONE,
    NAME_ONLY;

    public static final class Adapter extends GsonAdapter<PathSensitivity> {
        @Override
        public void write(JsonWriter out, PathSensitivity value) throws IOException {
            out.value(value.name().toLowerCase(Locale.ROOT));
        }

        @Override
        public PathSensitivity read(JsonReader in) throws IOException {
            return PathSensitivity.valueOf(in.nextString().toUpperCase(Locale.ROOT));
        }
    }
}
