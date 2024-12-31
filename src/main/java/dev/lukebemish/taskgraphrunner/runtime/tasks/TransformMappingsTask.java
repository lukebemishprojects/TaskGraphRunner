package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.MappingsFormat;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.mappings.MappingInheritance;
import dev.lukebemish.taskgraphrunner.runtime.mappings.MappingsSourceImpl;
import dev.lukebemish.taskgraphrunner.runtime.mappings.MappingsUtil;
import net.fabricmc.mappingio.tree.MappingTree;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TransformMappingsTask extends Task {
    private final MappingsFormat format;
    private final MappingsSourceImpl source;
    private final TaskInput.ValueInput formatValue;
    private final TaskInput.@Nullable HasFileInput sourceJarInput;

    public TransformMappingsTask(TaskModel.TransformMappings model, WorkItem workItem, Context context) {
        super(model);
        this.format = model.format;
        this.formatValue = new TaskInput.ValueInput("format", new Value.DirectStringValue(format.name()));
        this.source = MappingsSourceImpl.of(model.source, workItem, context, new AtomicInteger());
        this.sourceJarInput = model.sourceJar == null ? null : TaskInput.file("sourceJar", model.sourceJar, workItem, context, PathSensitivity.NONE);
    }

    @Override
    public List<TaskInput> inputs() {
        var inputs = new ArrayList<>(source.inputs());
        inputs.add(formatValue);
        if (sourceJarInput != null) {
            inputs.add(sourceJarInput);
        }
        return inputs;
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of("output", switch (format) {
            // Welp, here's my best guesses -- hard to find some of these in the wild
            case SRG -> "srg";
            case XSRG -> "xsrg";
            case CSRG -> "csrg";
            case TSRG, TSRG2 -> "tsrg";
            case PROGUARD -> "txt";
            case TINY -> "tiny";
            case TINY2 -> "tiny";
            case ENIGMA -> "mapping";
            case JAM -> "jam";
            case RECAF -> "txt";
            case JOBF -> "jobf";
            case PARCHMENT -> "json";
        });
    }

    @Override
    protected void run(Context context) {
        MappingTree mappings;
        if (sourceJarInput == null) {
            mappings = MappingsUtil.fixInnerClasses(source.makeMappings(context));
        } else {
            try {
                var path = sourceJarInput.path(context);
                var inheritance = MappingInheritance.read(path);
                mappings = MappingsUtil.fixInnerClasses(source.makeMappingsFillInheritance(context).make(inheritance));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try (var writer = Files.newBufferedWriter(context.taskOutputPath(this, "output"), StandardCharsets.UTF_8);
             var mappingsWriter = MappingsSourceImpl.getWriter(writer, format)) {
            mappingsWriter.accept(mappings);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
