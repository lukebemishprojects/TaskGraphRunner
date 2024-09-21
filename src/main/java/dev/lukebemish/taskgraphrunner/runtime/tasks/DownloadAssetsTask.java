package dev.lukebemish.taskgraphrunner.runtime.tasks;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.AssetsUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.DownloadUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DownloadAssetsTask extends Task {
    private final TaskInput.HasFileInput versionJson;

    public DownloadAssetsTask(TaskModel.DownloadAssets model, WorkItem workItem, Context context) {
        super(model.name());
        this.versionJson = TaskInput.file("versionJson", model.versionJson, workItem, context, PathSensitivity.NONE);
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(versionJson);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of(
            "properties", "properties",
            "json", "json"
        );
    }

    @Override
    protected boolean upToDate(long lastExecuted, Context context) {
        var propertiesPath = context.taskOutputPath(name(), "properties");
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var indexNumber = properties.getProperty("asset_index");
        var assetsRoot = new File(properties.getProperty("assets_root")).toPath();
        var targetIndex = assetsRoot.resolve("indexes").resolve(indexNumber + ".json");
        if (!Files.exists(targetIndex)) {
            return false;
        }
        return true;
    }

    @Override
    protected void run(Context context) {
        var versionJson = this.versionJson.path(context);
        try (var reader = Files.newBufferedReader(versionJson)) {
            var json = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            var assetIndex = json.getAsJsonObject("assetIndex");
            var assetIndexNumber = assetIndex.getAsJsonPrimitive("id").getAsString();

            // Now we need to download the asset index into the asset root
            var size = assetIndex.get("size").getAsLong();
            var sha1 = assetIndex.get("sha1").getAsString();
            var url = assetIndex.get("url").getAsString();
            var spec = new DownloadUtils.Spec.ChecksumAndSize(new URI(url), sha1, "SHA-1", size);
            var assetsRoot = AssetsUtils.findOrDownloadIndexAndAssets(spec, assetIndexNumber, context.assetOptions());

            var properties = new Properties();
            properties.setProperty("asset_index", assetIndexNumber);
            properties.setProperty("assets_root", assetsRoot.toAbsolutePath().toString());
            var output = context.taskOutputPath(name(), "properties");
            try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                properties.store(writer, "Generated by TaskGraphRunner");
            }

            var jsonOut = new JsonObject();
            jsonOut.addProperty("asset_index", assetIndexNumber);
            jsonOut.addProperty("assets", assetsRoot.toAbsolutePath().toString());
            var jsonOutput = context.taskOutputPath(name(), "json");
            try (var writer = Files.newBufferedWriter(jsonOutput, StandardCharsets.UTF_8)) {
                JsonUtils.GSON.toJson(jsonOut, writer);
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
