package dev.lukebemish.taskgraphmodel.runtime.tasks;

import dev.lukebemish.taskgraphmodel.model.TaskModel;
import dev.lukebemish.taskgraphmodel.runtime.Context;
import dev.lukebemish.taskgraphmodel.runtime.Task;
import dev.lukebemish.taskgraphmodel.runtime.TaskInput;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class DownloadManifestTask extends Task {
    public DownloadManifestTask(TaskModel.DownloadManifest model) {
        super(model.name());
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of();
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "json");
    }

    @Override
    protected void run(Context context) {
        Path outputPath = context.taskOutputPath(name(), "output");
        // TODO: download the manifest to that path, if it's out of date
    }
}
