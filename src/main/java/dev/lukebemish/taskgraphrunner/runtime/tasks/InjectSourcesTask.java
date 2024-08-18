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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InjectSourcesTask extends Task {
    private final TaskInput.HasFileInput input;
    private final TaskInput.HasFileInput sources;

    public InjectSourcesTask(TaskModel.InjectSources model, WorkItem workItem, Context context) {
        super(model.name());
        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        this.sources = TaskInput.file("sources", model.sources, workItem, context, PathSensitivity.NONE);
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(input, sources);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", "jar");
    }

    @Override
    protected void run(Context context) {
        Set<String> names = new HashSet<>();
        try (var iis = new BufferedInputStream(Files.newInputStream(input.path(context)));
             var sis = new BufferedInputStream(Files.newInputStream(sources.path(context)));
             var os = Files.newOutputStream(context.taskOutputPath(name(), "output"));
             var ziis = new ZipInputStream(iis);
             var zsis = new ZipInputStream(sis);
             var zos = new JarOutputStream(os)
        ) {
            ZipEntry entry;
            while ((entry = ziis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
                zos.putNextEntry(entry);
                ziis.transferTo(zos);
                zos.closeEntry();
            }
            while ((entry = zsis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (!names.contains(entry.getName())) {
                    zos.putNextEntry(entry);
                    zsis.transferTo(zos);
                    zos.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
