package dev.lukebemish.taskgraphrunner.runtime.tasks;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.DownloadUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class DownloadJsonTask extends Task {
    private final TaskInput.HasFileInput manifest;
    private final TaskInput.ValueInput version;

    public DownloadJsonTask(TaskModel.DownloadJson model, WorkItem workItem, Context context) {
        super(model.name());
        this.manifest = TaskInput.file("manifest", model.manifest, workItem, context, PathSensitivity.NONE);
        this.version = TaskInput.value("version", model.version, workItem);
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(manifest, version);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "json");
    }

    @Override
    protected void run(Context context) {
        try (var reader = Files.newBufferedReader(manifest.path(context))) {
            var json = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            var versions = json.getAsJsonArray("versions");
            var matching = versions.asList().stream()
                .filter(it ->
                    it.getAsJsonObject().get("id").getAsString().equals(version.value().value())
                ).findFirst();
            if (matching.isEmpty()) {
                throw new IllegalArgumentException("Version not found: " + version.value().value());
            }
            var version = matching.get().getAsJsonObject();
            var sha1 = version.get("sha1").getAsString();
            var url = version.get("url").getAsString();
            var spec = new DownloadUtils.Spec.Checksum(new URI(url), sha1, "SHA-1");
            var output = context.taskOutputPath(name(), "output");
            DownloadUtils.download(spec, output);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
