package dev.lukebemish.taskgraphrunner.runtime.mappings;

import dev.lukebemish.taskgraphrunner.model.MappingsFormat;
import dev.lukebemish.taskgraphrunner.model.MappingsSource;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import net.neoforged.srgutils.IMappingBuilder;
import net.neoforged.srgutils.IMappingFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public sealed interface MappingsSourceImpl {
    IMappingFile makeMappings(Context context);

    List<TaskInput> inputs();

    static IMappingFile.Format getFormat(MappingsFormat format) {
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

    static MappingsSourceImpl of(MappingsSource source, WorkItem workItem, Context context, AtomicInteger counter) {
        return switch (source) {
            case MappingsSource.Chained chained ->
                new ChainedSource(counter.getAndIncrement(), chained.sources.stream().map(s -> of(s, workItem, context, counter)).toList());
            case MappingsSource.ChainedFiles chainedFiles -> {
                List<TaskInput.FileListInput> filesParts = new ArrayList<>();
                for (var part : chainedFiles.files) {
                    filesParts.add(TaskInput.files("chainedFilesSource" + counter.getAndIncrement(), part, workItem, context, PathSensitivity.NONE));
                }
                yield new ChainedFiles(counter.getAndIncrement(), new TaskInput.RecursiveFileListInput("chainedFiles" + counter.getAndIncrement(), filesParts));
            }
            case MappingsSource.File file ->
                new FileSource(counter.getAndIncrement(), TaskInput.file("fileSource" + counter.getAndIncrement(), file.input, workItem, context, PathSensitivity.NONE));
            case MappingsSource.Merged merged ->
                new MergedSource(counter.getAndIncrement(), merged.sources.stream().map(s -> of(s, workItem, context, counter)).toList());
            case MappingsSource.MergedFiles mergedFiles -> {
                List<TaskInput.FileListInput> filesParts = new ArrayList<>();
                for (var part : mergedFiles.files) {
                    filesParts.add(TaskInput.files("mergedFilesSource" + counter.getAndIncrement(), part, workItem, context, PathSensitivity.NONE));
                }
                yield new MergedFiles(counter.getAndIncrement(), new TaskInput.RecursiveFileListInput("mergedFiles" + counter.getAndIncrement(), filesParts));
            }
            case MappingsSource.Reversed reversed ->
                new ReverseSource(counter.getAndIncrement(), of(reversed.source, workItem, context, counter));
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
            return mappings.reverse();
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
                var path = input.path(context);
                var extension = path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.') + 1);
                if ("zip".equals(extension) || "jar".equals(extension)) {
                    // It's an archive file; let's open it and find the entry we need, if we can, saving it to a temp file
                    try (var zip = new ZipFile(path.toFile())) {
                        var tinyMappings = zip.getEntry("mappings/mappings.tiny");
                        if (tinyMappings != null) {
                            var tempFile = Files.createTempFile("crochet-extracted", ".tiny");
                            try (var input = zip.getInputStream(tinyMappings)) {
                                Files.copy(input, tempFile);
                            }
                            return IMappingFile.load(tempFile.toFile());
                        }
                        var parchmentJson = zip.getEntry("parchment.json");
                        if (parchmentJson != null) {
                            var tempFile = Files.createTempFile("crochet-extracted", ".json");
                            try (var input = zip.getInputStream(parchmentJson)) {
                                Files.copy(input, tempFile);
                            }
                            return IMappingFile.load(tempFile.toFile());
                        }
                    }
                }
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
