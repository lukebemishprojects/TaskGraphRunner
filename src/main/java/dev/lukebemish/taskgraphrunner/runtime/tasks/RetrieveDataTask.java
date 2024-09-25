package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class RetrieveDataTask extends Task {
    private final TaskInput.HasFileInput input;
    private final TaskInput.ValueInput path;
    private final String extension;
    private final boolean isMakingZip;

    public RetrieveDataTask(TaskModel.RetrieveData model, WorkItem workItem, Context context) {
        super(model.name(), model.type());
        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        this.path = TaskInput.value("path", model.path, workItem);
        var pathToFind = path.value();
        if (!(pathToFind instanceof Value.StringValue stringValue)) {
            throw new IllegalArgumentException("Expected a string value for path, got "+pathToFind);
        }
        var pathString = stringValue.value();
        if (pathString.endsWith("/")) {
            this.extension = "zip";
            this.isMakingZip = true;
        } else {
            this.extension = pathString.substring(pathString.lastIndexOf('.')+1);
            this.isMakingZip = false;
        }
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(input, path);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", extension);
    }

    @Override
    protected void run(Context context) {
        var pathString = ((Value.StringValue) path.value()).value();
        if (isMakingZip) {
            try (var is = new BufferedInputStream(Files.newInputStream(input.path(context)));
                 var os = Files.newOutputStream(context.taskOutputPath(this, "output"));
                 var zis = new ZipInputStream(is);
                 var zos = new ZipOutputStream(os)
            ) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (entry.getName().startsWith(pathString)) {
                        var newEntry = new ZipEntry(entry.getName().substring(pathString.length()));
                        if (entry.getComment() != null) {
                            newEntry.setComment(entry.getComment());
                        }
                        if (entry.getCreationTime() != null) {
                            newEntry.setCreationTime(entry.getCreationTime());
                        }
                        if (entry.getLastModifiedTime() != null) {
                            newEntry.setLastModifiedTime(entry.getLastModifiedTime());
                        }
                        if (entry.getLastAccessTime() != null) {
                            newEntry.setLastAccessTime(entry.getLastAccessTime());
                        }
                        if (entry.getExtra() != null) {
                            newEntry.setExtra(entry.getExtra());
                        }
                        zos.putNextEntry(newEntry);
                        zis.transferTo(zos);
                        zos.closeEntry();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try (var is = new BufferedInputStream(Files.newInputStream(input.path(context)));
                 var os = Files.newOutputStream(context.taskOutputPath(this, "output"));
                 var zis = new ZipInputStream(is)) {
                boolean found = false;
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (entry.getName().equals(pathString)) {
                        zis.transferTo(os);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("No entry found for path `"+pathString+"`");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
