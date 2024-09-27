package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.Tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PatchSourcesTask extends JavaTask {
    private final TaskInput.HasFileInput input;
    private final TaskInput.HasFileInput patches;

    public PatchSourcesTask(TaskModel.PatchSources model, WorkItem workItem, Context context) {
        super(model);
        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        this.patches = TaskInput.file("patches", model.patches, workItem, context, PathSensitivity.NONE);
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(input, patches);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "zip", "rejects", "zip");
    }

    @Override
    protected void collectArguments(ArrayList<String> command, Context context, Path workingDirectory) {
        Path diffPatchPath = context.findArtifact(Tools.DIFF_PATCH);
        command.add("-jar");
        command.add(diffPatchPath.toString());
        command.addAll(List.of(
            input.path(context).toAbsolutePath().toString(), patches.path(context).toAbsolutePath().toString(),
            "--patch",
            "--archive", "ZIP",
            "--output", context.taskOutputPath(this, "output").toAbsolutePath().toString(),
            "--log-level", "WARN",
            "--mode", "OFFSET",
            "--archive-rejects", "ZIP",
            "--reject", context.taskOutputPath(this, "rejects").toAbsolutePath().toString()
        ));
    }
}
