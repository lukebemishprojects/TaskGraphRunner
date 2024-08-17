package dev.lukebemish.taskgraphrunner.runtime.tasks;

import com.google.gson.JsonObject;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.OsUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ListClasspathTask extends Task {
    private final TaskInput.HasFileInput versionJson;
    private final TaskInput.ValueListInput additionalLibraries;

    public ListClasspathTask(TaskModel.ListClasspath model, WorkItem workItem, Context context) {
        super(model.name());
        this.versionJson = TaskInput.file("versionJson", model.versionJson(), workItem, context, PathSensitivity.NONE);
        if (model.additionalLibraries() == null) {
            this.additionalLibraries = new TaskInput.ValueListInput("additionalLibraries", List.of());
        } else {
            this.additionalLibraries = TaskInput.values("additionalLibraries", model.additionalLibraries(), workItem);
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
                    var library = element.getAsJsonObject();
                    var name = library.get("name").getAsString();
                    var rules = library.getAsJsonArray("rules");
                    boolean[] allowed = new boolean[] {true};
                    if (rules != null) {
                        for (var ruleElement : rules) {
                            var rule = ruleElement.getAsJsonObject();
                            var action = rule.get("action").getAsString();
                            boolean[] passes = new boolean[] {true};
                            var os = rule.getAsJsonObject("os");
                            if (os != null) {
                                var osName = os.getAsJsonPrimitive("name").getAsString();
                                switch (osName) {
                                    case "osx" -> {
                                        if (!OsUtils.isMac()) {
                                            passes[0] = false;
                                        }
                                    }
                                    case "windows" -> {
                                        if (!OsUtils.isWindows()) {
                                            passes[0] = false;
                                        }
                                    }
                                    case "linux" -> {
                                        if (!OsUtils.isLinux()) {
                                            passes[0] = false;
                                        }
                                    }
                                }
                            }
                            if (action.equals("allow")) {
                                if (!passes[0]) {
                                    allowed[0] = false;
                                }
                            } else if (action.equals("disallow")) {
                                if (passes[0]) {
                                    allowed[0] = false;
                                }
                            }
                        }
                    }
                    if (!allowed[0]) {
                        continue;
                    }
                    artifacts.add("artifact:"+name);
                }
            }
            for (var input : additionalLibraries.inputs()) {
                artifacts.add((String) input.value());
            }
            Files.writeString(output, String.join(System.lineSeparator(), artifacts)+System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
