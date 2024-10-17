package dev.lukebemish.taskgraphrunner.model;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface InputHandle {
    Input getInput();
    void setInput(Input input);

    static InputHandle of(Supplier<Input> supplier, Consumer<Input> consumer) {
        return new InputHandle() {
            @Override
            public Input getInput() {
                return supplier.get();
            }

            @Override
            public void setInput(Input input) {
                consumer.accept(input);
            }
        };
    }

    static Stream<InputHandle> mutableList(List<Input> inputs) {
        return IntStream.range(0, inputs.size())
                .mapToObj(i -> of(() -> inputs.get(i), input -> inputs.set(i, input)));
    }
}
