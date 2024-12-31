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
    sealed interface StringValue extends Value {
        String value();
    }

    @JsonAdapter(ValueAdapter.class)
    record DirectStringValue(String value) implements StringValue {}

    @JsonAdapter(ValueAdapter.class)
    record SystemPropertyValue(String property, String defaultValue) implements StringValue {
        @Override
        public String value() {
            return System.getProperty(property, defaultValue);
        }
    }

    @JsonAdapter(ValueAdapter.class)
    record BooleanValue(Boolean value) implements Value {}

    @JsonAdapter(ValueAdapter.class)
    record ListValue(List<Value> value, ListOrdering listOrdering) implements Value {
        public ListValue(List<Value> value) {
            this(value, ListOrdering.ORIGINAL);
        }
    }

    @JsonAdapter(ValueAdapter.class)
    record MapValue(Map<String, Value> value) implements Value {}

    static DirectStringValue artifact(String notation) {
        return new DirectStringValue("artifact:" + notation);
    }

    static Value tool(String toolName) {
        return new DirectStringValue("tool:" + toolName);
    }

    static DirectStringValue file(Path path) {
        return new DirectStringValue("file:" + path.toAbsolutePath());
    }

}
