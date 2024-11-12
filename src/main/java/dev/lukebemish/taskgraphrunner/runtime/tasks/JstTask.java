package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.execution.ToolDaemonExecutor;
import dev.lukebemish.taskgraphrunner.runtime.mappings.MappingsSourceImpl;
import dev.lukebemish.taskgraphrunner.runtime.mappings.MappingsUtil;
import dev.lukebemish.taskgraphrunner.runtime.mappings.ParchmentMappingWriter;
import dev.lukebemish.taskgraphrunner.runtime.util.Tools;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

public class JstTask extends Task {
    private final TaskInput.HasFileInput input;
    private final TaskInput.FileListInput classpath;
    private final TaskInput.FileListInput executionClasspath;
    private final TaskInput.@Nullable FileListInput accessTransformers;
    private final TaskInput.@Nullable FileListInput interfaceInjection;
    private final @Nullable MappingsSourceImpl parchmentMappingsSource;
    private final List<TaskInput> inputs;
    private final List<ArgumentProcessor.Arg> args;
    private final Map<String, String> outputExtensions;
    private final boolean classpathScopedJvm;

    public JstTask(TaskModel.Jst model, WorkItem workItem, Context context) {
        super(model);

        this.inputs = new ArrayList<>();

        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        inputs.add(this.input);
        List<TaskInput.FileListInput> classpathParts = new ArrayList<>();
        for (int i = 0; i < model.classpath.size(); i++) {
            var part = model.classpath.get(i);
            classpathParts.add(TaskInput.files("classpath" + i, part, workItem, context, PathSensitivity.NONE));
        }
        this.classpath = new TaskInput.RecursiveFileListInput("classpath", classpathParts);
        inputs.add(this.classpath);

        List<TaskInput.FileListInput> executionClasspathParts = new ArrayList<>();
        for (int i = 0; i < model.executionClasspath.size(); i++) {
            var part = model.executionClasspath.get(i);
            executionClasspathParts.add(TaskInput.files("executionClasspath" + i, part, workItem, context, PathSensitivity.NONE));
        }
        this.executionClasspath = new TaskInput.RecursiveFileListInput("executionClasspath", executionClasspathParts);
        inputs.add(this.executionClasspath);

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
            this.parchmentMappingsSource = MappingsSourceImpl.of(model.parchmentData, workItem, context, new AtomicInteger());
            inputs.addAll(this.parchmentMappingsSource.inputs());
        } else {
            this.parchmentMappingsSource = null;
        }

        this.outputExtensions = new HashMap<>();
        outputExtensions.put("output", "zip");
        outputExtensions.put("stubs", "zip");

        this.args = new ArrayList<>();
        ArgumentProcessor.processArgs("arg", model.args, this.args, workItem, context, outputExtensions);

        this.classpathScopedJvm = model.classpathScopedJvm;
    }

    private void collectArguments(ArrayList<String> command, Context context, Path workingDirectory) {
        command.add(input.path(context).toAbsolutePath().toString());
        command.add(context.taskOutputPath(this, "output").toAbsolutePath().toString());

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
            command.add("--interface-injection-stubs="+context.taskOutputPath(this, "stubs"));
        }

        if (parchmentMappingsSource != null) {
            command.add("--enable-parchment");
            // Might eventually look at making this configurable
            command.add("--parchment-conflict-prefix=p");
            var parchmentFile = workingDirectory.resolve("parchment.json");
            var mappings = MappingsUtil.fixInnerClasses(parchmentMappingsSource.makeMappings(context));
            try (var writer = Files.newBufferedWriter(parchmentFile, StandardCharsets.UTF_8);
                 var mappingWriter = new ParchmentMappingWriter(writer)) {
                mappingWriter.accept(mappings);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            command.add("--parchment-mappings="+parchmentFile.toAbsolutePath());
            // Is this still necessary? Unclear, but javadoc don't seem to be working right
            command.add("--parchment-javadoc=true");
        }

        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            command.addAll(arg.resolve(workingDirectory, name(), context, i));
        }
    }

    @Override
    protected void run(Context context) {
        var workingDirectory = context.taskWorkingDirectory(this);
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var logFile = workingDirectory.resolve("log.txt");

        var command = new ArrayList<String>();
        collectArguments(command, context, workingDirectory);

        var mainClass = "net.neoforged.jst.cli.Main";
        var classpath = new ArrayList<Path>();
        classpath.add(context.findArtifact(Tools.JST).toAbsolutePath());
        classpath.addAll(this.executionClasspath.paths(context));

        try (var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            writer.append("-".repeat(80)).append("\n\n");
            writer.append("Command-Line:\n");
            writer.append(" -cp ").append(classpath.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator))).append("\n");
            writer.append(" - ").append(mainClass).append("\n");
            for (String s : command) {
                writer.append(" - ").append(s).append("\n");
            }
            writer.append("-".repeat(80)).append("\n\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ToolDaemonExecutor.execute(classpath, mainClass, logFile, command.toArray(String[]::new), context, classpathScopedJvm);

        if (this.interfaceInjection == null || this.interfaceInjection.paths(context).isEmpty()) {
            // Make an empty stubs zip
            var path = context.taskOutputPath(this, "stubs");
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
