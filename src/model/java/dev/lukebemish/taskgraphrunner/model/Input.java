package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;

import java.util.List;

@JsonAdapter(InputAdapter.class)
public sealed interface Input {
    @JsonAdapter(InputAdapter.class)
    record TaskInput(Output output) implements Input {}

    @JsonAdapter(InputAdapter.class)
    record ParameterInput(String parameter) implements Input {}

    @JsonAdapter(InputAdapter.class)
    record DirectInput(Value value) implements Input {}

    @JsonAdapter(InputAdapter.class)
    record ListInput(List<Input> inputs, ListOrdering listOrdering) implements Input {
        public ListInput(List<Input> inputs) {
            this(inputs, ListOrdering.ORIGINAL);
        }
    }
}
