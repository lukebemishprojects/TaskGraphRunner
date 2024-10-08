package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.taskgraphrunner.model.Config;
import dev.lukebemish.taskgraphrunner.model.Input;
import dev.lukebemish.taskgraphrunner.model.Output;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@CommandLine.Command(name = "mermaid", mixinStandardHelpOptions = true, description = "Transform a task graph into a mermaid flowchart")
class Mermaid implements Runnable {
    @CommandLine.Parameters(index = "0", description = "Configuration file.")
    Path config;

    @CommandLine.Parameters(index = "1", description = "Output file.")
    Path output;

    private final Main main;

    Mermaid(Main main) {
        this.main = main;
    }

    @Override
    public void run() {
        Config config;
        try (var reader = Files.newBufferedReader(this.config, StandardCharsets.UTF_8)) {
            config = JsonUtils.GSON.fromJson(reader, Config.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var visitor = new TaskGraphVisitor();
        for (var task : config.tasks) {
            visitor.visitTask(task);
        }
        config.aliases.forEach((alias, output) -> {
            var taskId = visitor.state.taskId(output.taskName());
            var outputId = visitor.state.outputId(output);
            var aliasId = visitor.state.idCounter++;
            visitor.state.addLine(visitor.state.taskOutputMaybeLabel(outputId, taskId, output) + " --> " + visitor.state.forId(aliasId) + "{" + alias + "}");
        });
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("flowchart LR");
            for (var line : visitor.state.lines) {
                writer.write("\n    ");
                writer.write(line);
            }
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class TaskGraphVisitor {
        private final State state = new State();

        void visitInput(Input input, String task) {
            switch (input) {
                case Input.ListInput listInput -> {
                    for (Input subInput : listInput.inputs()) {
                        visitInput(subInput, task);
                    }
                }
                case Input.ParameterInput parameterInput -> {
                    int taskId = state.taskId(task);
                    int id = state.parameterId(parameterInput.parameter());
                    state.addLine(state.parameterMaybeLabel(id, parameterInput.parameter()) + " --> " + state.taskMaybeLabel(taskId, task));
                }
                case Input.TaskInput taskInput -> {
                    int taskId = state.taskId(task);
                    int taskInId = state.taskId(taskInput.output().taskName());
                    int outputId = state.outputId(taskInput.output());
                    state.addLine(state.taskOutputMaybeLabel(outputId, taskInId, taskInput.output())+" --> " + state.taskMaybeLabel(taskId, task));
                }
                default -> {}
            }
        }

        void visitTask(TaskModel taskModel) {
            String name = taskModel.name();
            taskModel.inputs().forEach(input -> {
                visitInput(input, name);
            });
        }
    }

    private static class State {
        private final List<String> lines = new ArrayList<>();

        private int idCounter = 0;
        private final Map<String, Integer> taskIds = new HashMap<>();
        private final Map<String, Integer> parameters = new HashMap<>();
        private final Map<Output, Integer> taskOutputs = new HashMap<>();
        private final Set<Integer> hasLabelled = new HashSet<>();

        String parameterMaybeLabel(int id, String parameter) {
            if (hasLabelled.add(id)) {
                return forId(id) + ">" + parameter + "]";
            }
            return forId(id);
        }

        String taskMaybeLabel(int id, String task) {
            if (hasLabelled.add(id)) {
                return forId(id) + "[" + task + "]";
            }
            return forId(id);
        }

        String taskOutputMaybeLabel(int id, int taskId, Output output) {
            if (hasLabelled.add(id)) {
                return taskMaybeLabel(taskId, output.taskName()) + " --- " + forId(id) + "([" + output.name() + "])";
            }
            return forId(id);
        }

        String forId(int id) {
            StringBuilder sb = new StringBuilder();
            while (id >= 0) {
                sb.append((char) ('A' + id % 26));
                id /= 26;
                id -= 1;
            }
            return sb.reverse().toString();
        }

        int outputId(Output output) {
            return taskOutputs.computeIfAbsent(output, k -> idCounter++);
        }

        int taskId(String task) {
            return taskIds.computeIfAbsent(task, k -> idCounter++);
        }

        int parameterId(String parameter) {
            return parameters.computeIfAbsent(parameter, k -> idCounter++);
        }

        void addLine(String line) {
            lines.add(line);
        }
    }
}
