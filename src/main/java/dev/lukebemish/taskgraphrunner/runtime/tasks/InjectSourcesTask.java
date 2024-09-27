package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InjectSourcesTask extends Task {
    private final List<TaskInput.HasFileInput> inputs;

    public InjectSourcesTask(TaskModel.InjectSources model, WorkItem workItem, Context context) {
        super(model);
        this.inputs = new ArrayList<>();
        int count = 0;
        for (var input : model.inputs) {
            this.inputs.add(TaskInput.file("input"+count, input, workItem, context, PathSensitivity.NONE));
            count++;
        }
    }

    @Override
    public List<TaskInput> inputs() {
        return List.copyOf(inputs);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "jar");
    }

    @Override
    protected void run(Context context) {
        Set<String> names = new HashSet<>();
        try (var os = Files.newOutputStream(context.taskOutputPath(this, "output"));
             var zos = new JarOutputStream(os)) {
            for (var input : inputs) {
                try (var is = new BufferedInputStream(Files.newInputStream(input.path(context)));
                     var zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        if (names.add(entry.getName())) {
                            zos.putNextEntry(entry);
                            zis.transferTo(zos);
                            zos.closeEntry();
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
