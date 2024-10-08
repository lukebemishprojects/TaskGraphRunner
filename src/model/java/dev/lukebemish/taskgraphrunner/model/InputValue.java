package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;

import java.util.List;

@JsonAdapter(InputValueAdapter.class)
public sealed interface InputValue {
    @JsonAdapter(InputValueAdapter.class)
    record ParameterInput(String parameter) implements InputValue {}

    @JsonAdapter(InputValueAdapter.class)
    record DirectInput(Value value) implements InputValue {}

    @JsonAdapter(InputValueAdapter.class)
    record ListInput(List<InputValue> inputs) implements InputValue {}
}
