package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

public class SplitClassesResourcesTask extends Task {
    private final TaskInput.HasFileInput input;
    private final TaskInput.ValueInput excludePattern;

    public SplitClassesResourcesTask(TaskModel.SplitClassesResources model, WorkItem workItem, Context context) {
        super(model);
        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        if (model.excludePattern != null) {
            this.excludePattern = TaskInput.value("excludePattern", model.excludePattern, workItem, new Value.StringValue("META-INF/.*"));
        } else {
            this.excludePattern = new TaskInput.ValueInput("excludePattern", new Value.StringValue("META-INF/.*"));
        }
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(input);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "jar", "resources", "jar");
    }

    @Override
    protected void run(Context context) {
        var classesJar = context.taskOutputPath(this, "output");
        var resourcesJar = context.taskOutputPath(this, "resources");

        var deny = Pattern.compile((String) excludePattern.value().value()).asMatchPredicate();

        try (var input = new JarInputStream(new BufferedInputStream(Files.newInputStream(this.input.path(context))));
             var classesOutFile = new BufferedOutputStream(Files.newOutputStream(classesJar));
             var resourcesOutFile = new BufferedOutputStream(Files.newOutputStream(resourcesJar));
             var classesOutJar = new JarOutputStream(classesOutFile);
             var resourcesOutJar = new JarOutputStream(resourcesOutFile)
        ) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                var name = entry.getName();
                if (entry.isDirectory() || deny.test(name)) {
                    continue;
                }

                var destinationStream = name.endsWith(".class") ? classesOutJar : resourcesOutJar;

                destinationStream.putNextEntry(entry);
                input.transferTo(destinationStream);
                destinationStream.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
