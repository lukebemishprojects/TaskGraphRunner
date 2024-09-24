package dev.lukebemish.taskgraphrunner.runtime.tasks;

import dev.lukebemish.taskgraphrunner.model.MappingsFormat;
import dev.lukebemish.taskgraphrunner.model.MappingsSource;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import net.neoforged.srgutils.IMappingBuilder;
import net.neoforged.srgutils.IMappingFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class TransformMappingsTask extends Task {
    private final IMappingFile.Format format;
    private final MappingsSourceImpl source;
    private final TaskInput.ValueInput formatValue;

    public TransformMappingsTask(TaskModel.TransformMappings model, WorkItem workItem, Context context) {
        super(model.name());
        this.format = getFormat(model.format);
        this.formatValue = new TaskInput.ValueInput("format", new Value.StringValue(format.name()));
        this.source = MappingsSourceImpl.of(model.source, workItem, context, new AtomicInteger());
    }

    private static IMappingFile.Format getFormat(MappingsFormat format) {
        return switch (format) {
            case SRG -> IMappingFile.Format.SRG;
            case XSRG -> IMappingFile.Format.XSRG;
            case CSRG -> IMappingFile.Format.CSRG;
            case TSRG -> IMappingFile.Format.TSRG;
            case TSRG2 -> IMappingFile.Format.TSRG2;
            case PROGUARD -> IMappingFile.Format.PG;
            case TINY1 -> IMappingFile.Format.TINY1;
            case TINY2 -> IMappingFile.Format.TINY;
        };
    }

    private sealed interface MappingsSourceImpl {
        IMappingFile makeMappings(Context context);
        List<TaskInput> inputs();

        private static MappingsSourceImpl of(MappingsSource source, WorkItem workItem, Context context, AtomicInteger counter) {
            return switch (source) {
                case MappingsSource.Chained chained -> new ChainedSource(counter.getAndIncrement(), chained.sources.stream().map(s -> of(s, workItem, context, counter)).toList());
                case MappingsSource.ChainedFiles chainedFiles -> {
                    List<TaskInput.FileListInput> filesParts = new ArrayList<>();
                    for (var part : chainedFiles.files) {
                        filesParts.add(TaskInput.files("chainedFilesSource" + counter.getAndIncrement(), part, workItem, context, PathSensitivity.NONE));
                    }
                    yield new ChainedFiles(counter.getAndIncrement(), new TaskInput.RecursiveFileListInput("chainedFiles"+counter.getAndIncrement(), filesParts));
                }
                case MappingsSource.File file -> new FileSource(counter.getAndIncrement(), TaskInput.file("fileSource" + counter.getAndIncrement(), file.input, workItem, context, PathSensitivity.NONE));
                case MappingsSource.Merged merged -> new MergedSource(counter.getAndIncrement(), merged.sources.stream().map(s -> of(s, workItem, context, counter)).toList());
                case MappingsSource.MergedFiles mergedFiles -> {
                    List<TaskInput.FileListInput> filesParts = new ArrayList<>();
                    for (var part : mergedFiles.files) {
                        filesParts.add(TaskInput.files("mergedFilesSource" + counter.getAndIncrement(), part, workItem, context, PathSensitivity.NONE));
                    }
                    yield new MergedFiles(counter.getAndIncrement(), new TaskInput.RecursiveFileListInput("mergedFiles"+counter.getAndIncrement(), filesParts));
                }
                case MappingsSource.Reversed reversed -> new ReverseSource(counter.getAndIncrement(), of(reversed.source, workItem, context, counter));
            };
        }

        final class ChainedFiles implements MappingsSourceImpl {
            private final TaskInput.FileListInput files;
            private final TaskInput.ValueInput label;

            public ChainedFiles(int andIncrement, TaskInput.FileListInput files) {
                this.label = new TaskInput.ValueInput("chainedFilesLabel" + andIncrement, new Value.StringValue("chainedFiles" + andIncrement));
                this.files = files;
            }

            @Override
            public IMappingFile makeMappings(Context context) {
                return files.paths(context).stream()
                    .map(p -> {
                        try {
                            return IMappingFile.load(p.toFile());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .reduce(IMappingFile::chain).orElse(IMappingBuilder.create("source", "target").build().getMap("source", "target"));
            }

            @Override
            public List<TaskInput> inputs() {
                return List.of(label, files);
            }
        }

        final class ChainedSource implements MappingsSourceImpl {
            private final TaskInput.ValueInput label;
            private final List<MappingsSourceImpl> sources;

            public ChainedSource(int andIncrement, List<MappingsSourceImpl> sources) {
                this.label = new TaskInput.ValueInput("chainedLabel" + andIncrement + "_" + sources.size(), new Value.StringValue("chained" + andIncrement));
                this.sources = sources;
            }

            @Override
            public IMappingFile makeMappings(Context context) {
                return sources.stream()
                    .map(s -> s.makeMappings(context))
                    .reduce(IMappingFile::chain).orElse(IMappingBuilder.create("source", "target").build().getMap("source", "target"));
            }

            @Override
            public List<TaskInput> inputs() {
                var list = sources.stream()
                    .flatMap(source -> source.inputs().stream())
                    .collect(Collectors.toCollection(ArrayList::new));
                list.addFirst(label);
                return list;
            }
        }

        final class MergedFiles implements MappingsSourceImpl {
            private final TaskInput.ValueInput label;
            private final TaskInput.FileListInput files;

            public MergedFiles(int andIncrement, TaskInput.FileListInput files) {
                this.label = new TaskInput.ValueInput("mergedFilesLabel" + andIncrement, new Value.StringValue("mergedFiles" + andIncrement));
                this.files = files;
            }

            @Override
            public IMappingFile makeMappings(Context context) {
                return files.paths(context).stream()
                    .map(p -> {
                        try {
                            return IMappingFile.load(p.toFile());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .reduce(IMappingFile::merge).orElse(IMappingBuilder.create("source", "target").build().getMap("source", "target"));
            }

            @Override
            public List<TaskInput> inputs() {
                return List.of(label, files);
            }
        }

        final class MergedSource implements MappingsSourceImpl {
            private final TaskInput.ValueInput label;
            private final List<MappingsSourceImpl> sources;

            public MergedSource(int andIncrement, List<MappingsSourceImpl> sources) {
                this.label = new TaskInput.ValueInput("mergedLabel" + andIncrement + "_" + sources.size(), new Value.StringValue("merged" + andIncrement));
                this.sources = sources;
            }

            @Override
            public IMappingFile makeMappings(Context context) {
                return sources.stream()
                    .map(s -> s.makeMappings(context))
                    .reduce(IMappingFile::merge).orElse(IMappingBuilder.create("source", "target").build().getMap("source", "target"));
            }

            @Override
            public List<TaskInput> inputs() {
                var list = sources.stream()
                    .flatMap(source -> source.inputs().stream())
                    .collect(Collectors.toCollection(ArrayList::new));
                list.addFirst(label);
                return list;
            }
        }

        final class ReverseSource implements MappingsSourceImpl {
            private final TaskInput.ValueInput label;
            private final MappingsSourceImpl source;

            public ReverseSource(int andIncrement, MappingsSourceImpl source) {
                this.label = new TaskInput.ValueInput("reverseLabel" + andIncrement, new Value.StringValue("reverse" + andIncrement));
                this.source = source;
            }

            @Override
            public IMappingFile makeMappings(Context context) {
                IMappingFile mappings = source.makeMappings(context);
                mappings.reverse();
                return mappings;
            }

            @Override
            public List<TaskInput> inputs() {
                var list = new ArrayList<>(source.inputs());
                list.addFirst(label);
                return list;
            }
        }

        final class FileSource implements MappingsSourceImpl {
            private final TaskInput.ValueInput label;
            private final TaskInput.HasFileInput input;

            public FileSource(int andIncrement, TaskInput.HasFileInput input) {
                this.label = new TaskInput.ValueInput("fileLabel" + andIncrement, new Value.StringValue("file" + andIncrement));
                this.input = input;
            }

            @Override
            public IMappingFile makeMappings(Context context) {
                try {
                    return IMappingFile.load(input.path(context).toFile());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public List<TaskInput> inputs() {
                return List.of(label, input);
            }
        }
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
            case PG -> "txt";
            case TINY1, TINY -> "tiny";
        });
    }

    @Override
    protected void run(Context context) {
        IMappingFile mappings = source.makeMappings(context);
        try {
            mappings.write(context.taskOutputPath(this.name(), "output"), format, false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
