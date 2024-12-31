package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.annotations.JsonAdapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonAdapter(InputAdapter.class)
public sealed interface Input {
    @JsonAdapter(InputAdapter.class)
    sealed interface GeneralTaskInput extends Input {
        Output output(Map<String, Output> aliases);
    }

    @JsonAdapter(InputAdapter.class)
    record TaskInput(Output output) implements GeneralTaskInput {
        @Override
        public Output output(Map<String, Output> aliases) {
            return output;
        }
    }

    @JsonAdapter(InputAdapter.class)
    record AliasInput(String alias) implements GeneralTaskInput {
        @Override
        public Output output(Map<String, Output> aliases) {
            return Objects.requireNonNull(aliases.get(alias), "Unknown alias: " + alias);
        }
    }

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
