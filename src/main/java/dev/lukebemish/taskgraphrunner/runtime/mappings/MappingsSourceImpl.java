package dev.lukebemish.taskgraphrunner.runtime.mappings;

import dev.lukebemish.taskgraphrunner.model.MappingsFormat;
import dev.lukebemish.taskgraphrunner.model.MappingsSource;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.Value;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitableMappingTree;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public sealed interface MappingsSourceImpl {
    MappingTree makeMappings(Context context);

    MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context);

    List<TaskInput> inputs();

    interface MappingConsumer extends AutoCloseable {
        void accept(MappingTree mappings) throws IOException;

        @Override
        void close() throws IOException;

        static MappingConsumer wrap(MappingWriter writer) {
            return new MappingConsumer() {
                @Override
                public void accept(MappingTree mappings) throws IOException {
                    mappings.accept(writer);
                }

                @Override
                public void close() throws IOException {
                    writer.close();
                }
            };
        }
    }

    static MappingConsumer getWriter(Writer writer, MappingsFormat format) throws IOException {
        return switch (format) {
            case SRG -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.SRG_FILE));
            case XSRG -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.XSRG_FILE));
            case CSRG -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.CSRG_FILE));
            case TSRG -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.TSRG_FILE));
            case TSRG2 -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.TSRG_2_FILE));
            case PROGUARD -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.PROGUARD_FILE));
            case TINY -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.TINY_FILE));
            case TINY2 -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.TINY_2_FILE));
            case ENIGMA -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.ENIGMA_FILE));
            case JAM -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.JAM_FILE));
            case RECAF -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.RECAF_SIMPLE_FILE));
            case JOBF -> MappingConsumer.wrap(MappingWriter.create(writer, MappingFormat.JOBF_FILE));
            case PARCHMENT -> new ParchmentMappingWriter(writer);
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
                new FileSource(counter.getAndIncrement(), TaskInput.file("fileSource" + counter.getAndIncrement(), file.input, workItem, context, PathSensitivity.NONE), file.extension == null ? null : TaskInput.value("fileSource"+counter.get()+"extension", file.extension, workItem));
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
            this.label = new TaskInput.ValueInput("chainedFilesLabel" + andIncrement, new Value.DirectStringValue("chainedFiles" + andIncrement));
            this.files = files;
        }

        @Override
        public MappingTree makeMappings(Context context) {
            return MappingsUtil.chain(files.paths(context).stream()
                .map(p -> {
                    try {
                        return loadMappings(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList());
        }

        @Override
        public MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context) {
            return inheritance -> MappingsUtil.filledChain(inheritance, files.paths(context).stream()
                .map(p -> {
                    try {
                        return MappingsUtil.provider(loadMappings(p));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList());
        }

        @Override
        public List<TaskInput> inputs() {
            return List.of(label, files);
        }
    }

    private static MappingTree loadMappings(Path path) throws IOException {
        var extension = path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.') + 1);
        if ("zip".equals(extension) || "jar".equals(extension)) {
            // It's an archive file; let's open it and find the entry we need, if we can, saving it to a temp file
            try (var zip = new ZipFile(path.toFile())) {
                var tinyMappings = zip.getEntry("mappings/mappings.tiny");
                if (tinyMappings != null) {
                    var tempFile = Files.createTempFile("crochet-extracted", ".tiny");
                    try (var input = zip.getInputStream(tinyMappings)) {
                        Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return loadMappings(tempFile);
                }
                var parchmentJson = zip.getEntry("parchment.json");
                if (parchmentJson != null) {
                    var tempFile = Files.createTempFile("crochet-extracted", ".json");
                    try (var input = zip.getInputStream(parchmentJson)) {
                        Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return loadMappings(tempFile);
                }
            }
        } else if ("json".equals(extension)) {
            return ParchmentMappingsReader.loadMappings(path);
        }
        VisitableMappingTree tree = new MemoryMappingTree();
        MappingReader.read(path, tree);
        return tree;
    }

    final class ChainedSource implements MappingsSourceImpl {
        private final TaskInput.ValueInput label;
        private final List<MappingsSourceImpl> sources;

        public ChainedSource(int andIncrement, List<MappingsSourceImpl> sources) {
            this.label = new TaskInput.ValueInput("chainedLabel" + andIncrement + "_" + sources.size(), new Value.DirectStringValue("chained" + andIncrement));
            this.sources = sources;
        }

        @Override
        public MappingTree makeMappings(Context context) {
            return MappingsUtil.chain(sources.stream()
                .map(s -> s.makeMappings(context))
                .toList());
        }

        @Override
        public MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context) {
            return inheritance -> MappingsUtil.filledChain(inheritance, sources.stream()
                .map(s -> s.makeMappingsFillInheritance(context))
                .toList());
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
            this.label = new TaskInput.ValueInput("mergedFilesLabel" + andIncrement, new Value.DirectStringValue("mergedFiles" + andIncrement));
            this.files = files;
        }

        @Override
        public MappingTree makeMappings(Context context) {
            return MappingsUtil.merge(files.paths(context).stream()
                .map(p -> {
                    try {
                        return loadMappings(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList());
        }

        @Override
        public MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context) {
            return inheritance -> MappingsUtil.filledMerge(inheritance, files.paths(context).stream()
                .map(p -> {
                    try {
                        return MappingsUtil.provider(loadMappings(p));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList());
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
            this.label = new TaskInput.ValueInput("mergedLabel" + andIncrement + "_" + sources.size(), new Value.DirectStringValue("merged" + andIncrement));
            this.sources = sources;
        }

        @Override
        public MappingTree makeMappings(Context context) {
            return MappingsUtil.merge(sources.stream()
                .map(s -> s.makeMappings(context))
                .toList());
        }

        @Override
        public MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context) {
            return inheritance -> MappingsUtil.filledMerge(inheritance, sources.stream()
                .map(s -> s.makeMappingsFillInheritance(context))
                .toList());
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
            this.label = new TaskInput.ValueInput("reverseLabel" + andIncrement, new Value.DirectStringValue("reverse" + andIncrement));
            this.source = source;
        }

        @Override
        public MappingTree makeMappings(Context context) {
            MappingTree mappings = source.makeMappings(context);
            return MappingsUtil.reverse(mappings);
        }

        @Override
        public MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context) {
            var sourceMappings = source.makeMappingsFillInheritance(context);
            return inheritance -> {
                MappingTree mappings = sourceMappings.make(inheritance);
                return MappingsUtil.reverse(mappings);
            };
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
        private final TaskInput.@Nullable ValueInput extension;

        public FileSource(int andIncrement, TaskInput.HasFileInput input, TaskInput.@Nullable ValueInput extension) {
            this.label = new TaskInput.ValueInput("fileLabel" + andIncrement, new Value.DirectStringValue("file" + andIncrement));
            this.input = input;
            this.extension = extension;
        }

        @Override
        public MappingTree makeMappings(Context context) {
            try {
                var path = input.path(context);
                if (extension != null) {
                    var extensionObj = extension.value().value();
                    if (extensionObj instanceof String extensionString) {
                        var tempFile = Files.createTempFile("crochet-extracted", "." + extensionString);
                        try (var input = Files.newInputStream(path)) {
                            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        path = tempFile;
                    } else {
                        throw new IllegalArgumentException("Extension value is not a string");
                    }
                }
                return loadMappings(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public MappingsUtil.MappingProvider makeMappingsFillInheritance(Context context) {
            var mappings = makeMappings(context);
            return MappingsUtil.provider(mappings);
        }

        @Override
        public List<TaskInput> inputs() {
            if (extension == null) {
                return List.of(label, input);
            } else {
                return List.of(label, input, extension);
            }
        }
    }
}
