package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.Tools;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

public class JstTask extends JavaTask {
    private final TaskInput.HasFileInput input;
    private final TaskInput.FileListInput classpath;
    private final TaskInput.@Nullable FileListInput accessTransformers;
    private final TaskInput.@Nullable FileListInput interfaceInjection;
    private final TaskInput.@Nullable HasFileInput parchmentData;
    private final List<TaskInput> inputs;
    private final List<ArgumentProcessor.Arg> args;
    private final Map<String, String> outputExtensions;

    public JstTask(TaskModel.Jst model, WorkItem workItem, Context context) {
        super(model.name());

        this.inputs = new ArrayList<>();

        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        inputs.add(this.input);
        this.classpath = TaskInput.files("classpath", model.classpath, workItem, context, PathSensitivity.NONE);
        inputs.add(this.classpath);
        if (model.accessTransformers != null) {
            this.accessTransformers = TaskInput.files("accessTransformers", model.accessTransformers, workItem, context, PathSensitivity.NONE);
            inputs.add(this.accessTransformers);
        } else {
            this.accessTransformers = null;
        }
        if (model.interfaceInjection != null) {
            this.interfaceInjection = TaskInput.files("interfaceInjection", model.interfaceInjection, workItem, context, PathSensitivity.NONE);
            inputs.add(this.interfaceInjection);
        } else {
            this.interfaceInjection = null;
        }
        if (model.parchmentData != null) {
            this.parchmentData = TaskInput.file("parchmentData", model.parchmentData, workItem, context, PathSensitivity.NONE);
            inputs.add(this.parchmentData);
        } else {
            this.parchmentData = null;
        }

        this.outputExtensions = new HashMap<>();
        outputExtensions.put("output", "zip");
        outputExtensions.put("stubs", "zip");

        this.args = new ArrayList<>();
        ArgumentProcessor.processArgs(model.args, this.args, workItem, context, outputExtensions);
    }

    @Override
    protected void collectArguments(ArrayList<String> command, Context context, Path workingDirectory) {
        command.add("-jar");
        command.add(context.findArtifact(Tools.JST).toAbsolutePath().toString());

        command.add(input.path(context).toAbsolutePath().toString());
        command.add(context.taskOutputPath(name(), "output").toAbsolutePath().toString());

        command.add("--classpath="+classpath.classpath(context));
        command.add("--in-format=ARCHIVE");
        command.add("--out-format=ARCHIVE");

        if (accessTransformers != null && !accessTransformers.paths(context).isEmpty()) {
            command.add("--enable-accesstransformers");
            for (var path : accessTransformers.paths(context)) {
                command.add("--access-transformer="+path.toAbsolutePath());
            }
        }

        if (interfaceInjection != null && !interfaceInjection.paths(context).isEmpty()) {
            command.add("--enable-interface-injection");
            for (var path : interfaceInjection.paths(context)) {
                command.add("--interface-injection-data="+path.toAbsolutePath());
            }
            command.add("--interface-injection-stubs="+context.taskOutputPath(name(), "stubs"));
        }

        if (parchmentData != null) {
            command.add("--enable-parchment");
            command.add("--parchment-mappings="+parchmentData.path(context).toAbsolutePath());
        }

        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            command.addAll(arg.resolve(workingDirectory, name(), context, i));
        }
    }

    @Override
    protected void run(Context context) {
        super.run(context);
        if (this.interfaceInjection == null || this.interfaceInjection.paths(context).isEmpty()) {
            // Make an empty stubs zip
            var path = context.taskOutputPath(name(), "stubs");
            try (var os = Files.newOutputStream(path);
                 var zos = new ZipOutputStream(os)) {
                zos.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public List<TaskInput> inputs() {
        return inputs;
    }

    @Override
    public Map<String, String> outputTypes() {
        return outputExtensions;
    }
}