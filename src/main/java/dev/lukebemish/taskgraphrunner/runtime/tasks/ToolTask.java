package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolTask extends JavaTask {
    private final List<ArgumentProcessor.Arg> args;
    private final List<TaskInput> inputs;
    private final Map<String, String> outputExtensions;

    public ToolTask(TaskModel.Tool model, WorkItem workItem, Context context) {
        super(model);

        this.outputExtensions = new HashMap<>();

        this.args = new ArrayList<>();
        ArgumentProcessor.processArgs("arg", model.args, this.args, workItem, context, outputExtensions);

        this.inputs = args.stream().flatMap(ArgumentProcessor.Arg::inputs).toList();
    }

    @Override
    public List<TaskInput> inputs() {
        return this.inputs;
    }

    @Override
    public Map<String, String> outputTypes() {
        return outputExtensions;
    }

    @Override
    protected void collectArguments(ArrayList<String> command, Context context, Path workingDirectory) {
        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            command.addAll(arg.resolve(workingDirectory, name(), context, i));
        }
    }
}
