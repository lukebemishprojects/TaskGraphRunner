package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.MappingsFormat;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.mappings.MappingsSourceImpl;

import java.io.IOException;
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

    public TransformMappingsTask(TaskModel.TransformMappings model, WorkItem workItem, Context context) {
        super(model);
        this.format = model.format;
        this.formatValue = new TaskInput.ValueInput("format", new Value.StringValue(format.name()));
        this.source = MappingsSourceImpl.of(model.source, workItem, context, new AtomicInteger());
    }

    @Override
    public List<TaskInput> inputs() {
        var inputs = new ArrayList<>(source.inputs());
        inputs.add(formatValue);
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
        var mappings = source.makeMappings(context);
        try (var writer = Files.newBufferedWriter(context.taskOutputPath(this, "output"), StandardCharsets.UTF_8);
             var mappingsWriter = MappingsSourceImpl.getWriter(writer, format)) {
            mappingsWriter.accept(mappings);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
