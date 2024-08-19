package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CompileTask extends Task {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompileTask.class);

    private final List<ArgumentProcessor.Arg> args;
    private final List<TaskInput> inputs;
    private final Map<String, String> outputExtensions;
    private final TaskInput.HasFileInput sources;
    private final TaskInput.FileListInput classpath;
    private final TaskInput.FileListInput sourcepath;

    public CompileTask(TaskModel.Compile model, WorkItem workItem, Context context) {
        super(model.name());

        this.outputExtensions = new HashMap<>();
        outputExtensions.put("output", "jar");

        this.args = new ArrayList<>();
        ArgumentProcessor.processArgs(model.args, this.args, workItem, context, outputExtensions);

        this.inputs = new ArrayList<>(args.stream().flatMap(ArgumentProcessor.Arg::inputs).toList());
        this.sources = TaskInput.file("sources", model.sources, workItem, context, PathSensitivity.NONE);
        this.classpath = TaskInput.files("classpath", model.classpath, workItem, context, PathSensitivity.NONE);
        this.sourcepath = TaskInput.files("sourcepath", model.sourcepath, workItem, context, PathSensitivity.NONE);
        this.inputs.add(sources);
        this.inputs.add(classpath);
        this.inputs.add(sourcepath);
    }

    @Override
    public List<TaskInput> inputs() {
        return this.inputs;
    }

    @Override
    public Map<String, String> outputTypes() {
        return outputExtensions;
    }

    @Override
    protected void run(Context context) {
        var sourcesJar = this.sources.path(context);

        var workingDirectory = context.taskWorkingDirectory(name());
        var logFile = workingDirectory.resolve("log.txt");
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var options = new ArrayList<String>();
        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            options.addAll(arg.resolve(workingDirectory, name(), context, i));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();

        var entries = new ArrayList<String>();
        try (var is = Files.newInputStream(sourcesJar);
             var zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().isBlank()) {
                    continue;
                }
                entries.add(entry.getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (var sources = FileSystems.newFileSystem(URI.create("jar:" + sourcesJar.toUri()), Map.of("create", false))) {
            var sourceRoot = sources.getRootDirectories().iterator().next();
            List<Path> javaSources = new ArrayList<>();
            List<Path> resources = new ArrayList<>();
            for (var entry : entries) {
                var path = sourceRoot.resolve(entry);
                if (Files.isDirectory(path)) {
                    continue;
                }
                if (entry.endsWith(".java")) {
                    javaSources.add(path);
                } else {
                    resources.add(path);
                }
            }

            var diagnostics = new DiagnosticListener<JavaFileObject>() {
                @Override
                public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                    var sourceFile = diagnostic.getSource() == null ? "<unknown>" : diagnostic.getSource().getName();
                    var position = diagnostic.getPosition();
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        if (position == Diagnostic.NOPOS) {
                            LOGGER.error("{}: in {}: {}", diagnostic.getKind(), sourceFile, diagnostic.getMessage(null));
                        } else {
                            LOGGER.error("{}: in {}, line {}, column {}: {}", diagnostic.getKind(), sourceFile, diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getMessage(null));
                        }
                    } else {
                        if (position == Diagnostic.NOPOS) {
                            LOGGER.debug("{}: in {}: {}", diagnostic.getKind(), sourceFile, diagnostic.getMessage(null));
                        } else {
                            LOGGER.debug("{}: in {}, line {}, column {}: {}", diagnostic.getKind(), sourceFile, diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getMessage(null));
                        }
                    }
                }
            };

            var outputJar = context.taskOutputPath(name(), "output");
            if (Files.exists(outputJar)) {
                Files.delete(outputJar);
            }
            try (var output = FileSystems.newFileSystem(URI.create("jar:" + outputJar.toUri()), Map.of("create", true))) {
                var outputRoot = output.getRootDirectories().iterator().next();

                for (var resource : resources) {
                    var destination = outputRoot.resolve(sourceRoot.relativize(resource).toString());
                    Files.createDirectories(destination.getParent());
                    Files.copy(resource, destination);
                }

                try (var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
                     var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                    writer.append("-".repeat(80)).append("\n\n");
                    writer.append("Compiler Arguments:\n");
                    for (String s : options) {
                        writer.append(" - ").append(s).append("\n");
                    }
                    writer.append("-".repeat(80)).append("\n\n");

                    files.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath.paths(context));
                    files.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcepath.paths(context));
                    files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputRoot));

                    var fileObjects = files.getJavaFileObjectsFromPaths(javaSources);

                    if (!compiler.getTask(writer, files, diagnostics, options, null, fileObjects).call()) {
                        throw new RuntimeException("Compilation failed");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
