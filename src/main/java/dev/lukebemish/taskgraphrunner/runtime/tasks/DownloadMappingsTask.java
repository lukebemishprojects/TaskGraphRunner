package dev.lukebemish.taskgraphrunner.runtime.tasks;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.Distribution;
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

public class DownloadMappingsTask extends Task {
    private final TaskInput.ValueInput distribution;
    private final TaskInput.HasFileInput versionJson;

    public DownloadMappingsTask(TaskModel.DownloadMappings model, WorkItem workItem, Context context) {
        super(model.name(), model.type());
        this.distribution = TaskInput.value("distribution", model.distribution, workItem);
        this.versionJson = TaskInput.file("versionJson", model.versionJson, workItem, context, PathSensitivity.NONE);
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(distribution, versionJson);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "txt");
    }

    @Override
    protected void run(Context context) {
        var distribution = Distribution.fromString((String) this.distribution.value().value());
        if (distribution == Distribution.JOINED) {
            throw new IllegalArgumentException("Distribution JOINED cannot be downloaded");
        }
        var versionJson = this.versionJson.path(context);
        try (var reader = Files.newBufferedReader(versionJson)) {
            var json = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            var downloads = json.getAsJsonObject("downloads");
            var download = downloads.getAsJsonObject(distribution == Distribution.CLIENT ? "client_mappings" : "server_mappings");
            var size = download.get("size").getAsLong();
            var sha1 = download.get("sha1").getAsString();
            var url = download.get("url").getAsString();
            var spec = new DownloadUtils.Spec.ChecksumAndSize(new URI(url), sha1, "SHA-1", size);
            var output = context.taskOutputPath(this, "output");
            DownloadUtils.download(spec, output);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
