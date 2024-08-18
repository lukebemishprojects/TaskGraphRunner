package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(InputAdapter.class)
public sealed interface Input {
    @JsonAdapter(InputAdapter.class)
    record TaskInput(Output output) implements Input {}

    @JsonAdapter(InputAdapter.class)
    record ParameterInput(String parameter) implements Input {}

    @JsonAdapter(InputAdapter.class)
    record DirectInput(Value value) implements Input {}

}
