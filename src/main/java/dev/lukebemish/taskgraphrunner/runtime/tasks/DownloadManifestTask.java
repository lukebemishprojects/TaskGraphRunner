package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.DownloadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class DownloadManifestTask extends Task {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadManifestTask.class);

    public DownloadManifestTask(TaskModel.DownloadManifest model) {
        super(model.name(), model.type());
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
        Path outputPath = context.taskOutputPath(this, "output");

        boolean exists = Files.exists(outputPath);

        try {
            var uri = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");
            DownloadUtils.download(new DownloadUtils.Spec.Simple(uri), outputPath);
        } catch (IOException e) {
            if (exists) {
                // The file already exists, so lets ignore this error for now but lets log it
                LOGGER.warn("Failed to download manifest, using existing file: " + e.getMessage());
            } else {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    protected boolean upToDate(long lastExecuted, Context context) {
        // Up to date if we've checked within 5 minutes
        return System.currentTimeMillis() - lastExecuted < 1000 * 60 * 5; // 5 minutes
    }
}
