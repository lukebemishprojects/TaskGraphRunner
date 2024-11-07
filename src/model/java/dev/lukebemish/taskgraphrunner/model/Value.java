package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@JsonAdapter(ValueAdapter.class)
public sealed interface Value {
    Object value();

    @JsonAdapter(ValueAdapter.class)
    record NumberValue(Number value) implements Value {}

    @JsonAdapter(ValueAdapter.class)
    record StringValue(String value) implements Value {}

    @JsonAdapter(ValueAdapter.class)
    record BooleanValue(Boolean value) implements Value {}

    @JsonAdapter(ValueAdapter.class)
    record ListValue(List<Value> value, ListContentsHashStrategy listContentsHashStrategy) implements Value {
        public ListValue(List<Value> value) {
            this(value, ListContentsHashStrategy.ORIGINAL);
        }
    }

    @JsonAdapter(ValueAdapter.class)
    record MapValue(Map<String, Value> value) implements Value {}

    static StringValue artifact(String notation) {
        return new StringValue("artifact:" + notation);
    }

    static Value tool(String toolName) {
        return new StringValue("tool:" + toolName);
    }

    static StringValue file(Path path) {
        return new StringValue("file:" + path.toAbsolutePath());
    }

}
