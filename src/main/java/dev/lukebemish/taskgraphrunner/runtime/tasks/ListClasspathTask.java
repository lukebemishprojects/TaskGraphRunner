package dev.lukebemish.taskgraphrunner.runtime.tasks;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.manifest.version.Library;
import dev.lukebemish.taskgraphrunner.runtime.manifest.version.Rule;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ListClasspathTask extends Task {
    private final TaskInput.HasFileInput versionJson;
    private final TaskInput.ValueInput additionalLibraries;

    public ListClasspathTask(TaskModel.ListClasspath model, WorkItem workItem, Context context) {
        super(model.name());
        this.versionJson = TaskInput.file("versionJson", model.versionJson, workItem, context, PathSensitivity.NONE);
        if (model.additionalLibraries == null) {
            this.additionalLibraries = new TaskInput.ValueInput("additionalLibraries", new Value.ListValue(List.of()));
        } else {
            this.additionalLibraries = TaskInput.value("additionalLibraries", model.additionalLibraries, workItem);
        }
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(versionJson);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "txt");
    }

    @Override
    protected void run(Context context) {
        var versionManifest = versionJson.path(context);
        var output = context.taskOutputPath(name(), "output");

        try (var reader = Files.newBufferedReader(versionManifest)) {
            var json = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            var libraries = json.getAsJsonArray("libraries");
            List<String> artifacts = new ArrayList<>();
            if (libraries != null) {
                for (var element : libraries) {
                    var library = JsonUtils.GSON.fromJson(element, Library.class);
                    boolean allowed = true;
                    for (var rule : library.rules()) {
                        if (rule.action() == Rule.Action.ALLOW) {
                            if (!rule.matches()) {
                                allowed = false;
                                break;
                            }
                        } else {
                            if (rule.matches()) {
                                allowed = false;
                                break;
                            }
                        }
                    }
                    if (!allowed) {
                        continue;
                    }
                    artifacts.add("artifact:"+library.name());
                }
            }
            if (!(additionalLibraries.value() instanceof Value.ListValue additionalLibrariesList)) {
                throw new IllegalArgumentException("additionalLibraries must be a list");
            }
            for (var input : additionalLibrariesList.value()) {
                if (!(input instanceof Value.StringValue stringValue)) {
                    throw new IllegalArgumentException("additionalLibraries must be a list of strings");
                }
                artifacts.add(stringValue.value());
            }
            Files.writeString(output, String.join(System.lineSeparator(), artifacts)+System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
