package dev.lukebemish.taskgraphrunner.runtime.zips;

import com.google.protobuf.UnsafeByteOperations;
import dev.lukebemish.taskgraphrunner.runtime.Invocation;
import dev.lukebemish.taskgraphrunner.runtime.util.HashUtils;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PiecewiseZips {
    private static final ExecutorService PARALLEL_EXECUTOR = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("TaskGraphRunner-Zip-", 1).factory());

    private final Invocation invocation;

    public PiecewiseZips(Invocation invocation) {
        this.invocation = invocation;
    }

    private record FileCompressed(int offset, int length) implements Comparable<FileCompressed> {
        @Override
        public int compareTo(FileCompressed o) {
            return Integer.compare(this.offset, o.offset);
        }
    }

    public void disassemble(Path inputZip, Path outputZipPartsFile) throws IOException {
        var builder = ZipFile.newBuilder();
        byte[] zipFileBytes = Files.readAllBytes(inputZip);
        var fileSize = zipFileBytes.length;

        var files = new ArrayList<FileCompressed>();

        var maxEocdLength = 1 << 16; // 64KB
        boolean foundValidZip = false;
        outer: for (int i = fileSize - 22; i >= Math.max(0, fileSize - maxEocdLength); i--) {
            files.clear();
            if (zipFileBytes[i] == 0x50 && zipFileBytes[i + 1] == 0x4b && zipFileBytes[i + 2] == 0x05 && zipFileBytes[i + 3] == 0x06) {
                // found EOCD signature
                int commentLength = ((zipFileBytes[i + 20] & 0xFF) | ((zipFileBytes[i + 21] & 0xFF) << 8));
                int eocdLength = 22 + commentLength;
                if (i + eocdLength != fileSize) {
                    continue;
                }
                int totalCDRecords = (zipFileBytes[i + 10] & 0xFF) | ((zipFileBytes[i + 11] & 0xFF) << 8);
                int cdSize = ((zipFileBytes[i + 12] & 0xFF) | ((zipFileBytes[i + 13] & 0xFF) << 8) | ((zipFileBytes[i + 14] & 0xFF) << 16) | ((zipFileBytes[i + 15] & 0xFF) << 24));
                int cdOffset = ((zipFileBytes[i + 16] & 0xFF) | ((zipFileBytes[i + 17] & 0xFF) << 8) | ((zipFileBytes[i + 18] & 0xFF) << 16) | ((zipFileBytes[i + 19] & 0xFF) << 24));
                int cdStart = cdOffset;
                for (int j = 0; j < totalCDRecords; j++) {
                    if (cdStart < 0 || cdStart + 46 > fileSize) {
                        continue outer;
                    }
                    if (zipFileBytes[cdStart] != 0x50 || zipFileBytes[cdStart + 1] != 0x4b || zipFileBytes[cdStart + 2] != 0x01 || zipFileBytes[cdStart + 3] != 0x02) {
                        continue outer;
                    }
                    int fileNameLength = ((zipFileBytes[cdStart + 28] & 0xFF) | ((zipFileBytes[cdStart + 29] & 0xFF) << 8));
                    int extraFieldLength = ((zipFileBytes[cdStart + 30] & 0xFF) | ((zipFileBytes[cdStart + 31] & 0xFF) << 8));
                    int fileCommentLength = ((zipFileBytes[cdStart + 32] & 0xFF) | ((zipFileBytes[cdStart + 33] & 0xFF) << 8));
                    int recordSize = 46 + fileNameLength + extraFieldLength + fileCommentLength;
                    if (cdStart + recordSize > fileSize) {
                        continue outer;
                    }
                    int compressedSize = ((zipFileBytes[cdStart + 20] & 0xFF) | ((zipFileBytes[cdStart + 21] & 0xFF) << 8) | ((zipFileBytes[cdStart + 22] & 0xFF) << 16) | ((zipFileBytes[cdStart + 23] & 0xFF) << 24));
                    int localHeaderStart = ((zipFileBytes[cdStart + 42] & 0xFF) | ((zipFileBytes[cdStart + 43] & 0xFF) << 8) | ((zipFileBytes[cdStart + 44] & 0xFF) << 16) | ((zipFileBytes[cdStart + 45] & 0xFF) << 24));
                    if (localHeaderStart < 0 || localHeaderStart + 30 > fileSize) {
                        continue outer;
                    }
                    cdStart += recordSize;

                    if (zipFileBytes[localHeaderStart] != 0x50 || zipFileBytes[localHeaderStart + 1] != 0x4b || zipFileBytes[localHeaderStart + 2] != 0x03 || zipFileBytes[localHeaderStart + 3] != 0x04) {
                        continue outer;
                    }
                    if (localHeaderStart + 30 + compressedSize > fileSize) {
                        continue outer;
                    }
                    // TODO: We could make this configurable?
                    if (compressedSize < 1) {
                        // skip small/empty files
                        continue;
                    }
                    files.add(new FileCompressed(localHeaderStart, compressedSize));
                }
                if (cdStart != cdOffset + cdSize) {
                    continue;
                }
                foundValidZip = true;
                break;
            }
        }

        if (!foundValidZip) {
            throw new IOException("Could not find valid EOCD record in zip file: " + inputZip);
        }

        int head = 0;
        record OrRef(@Nullable ZipEntry entry, @Nullable Future<ZipEntry> future) {}
        List<OrRef> partsList = new ArrayList<>();
        for (var file : files) {
            if (file.offset > head) {
                partsList.add(new OrRef(ZipEntry.newBuilder().setSimplePart(SimpleZipPart.newBuilder()
                    .setRawData(UnsafeByteOperations.unsafeWrap(zipFileBytes, head, file.offset - head))
                ).build(), null));
                head = file.offset;
            }
            if (file.offset == head) {
                partsList.add(new OrRef(null, PARALLEL_EXECUTOR.submit(() -> {
                    try {
                        return referenceEntry(file, zipFileBytes);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })));

                head += file.length;
            } else {
                throw new IOException("Overlapping local files in zip disassembly");
            }
        }
        if (head < fileSize) {
            partsList.add(new OrRef(ZipEntry.newBuilder().setSimplePart(SimpleZipPart.newBuilder()
                .setRawData(UnsafeByteOperations.unsafeWrap(zipFileBytes, head, fileSize - head))
            ).build(), null));
        }
        for (var orRef : partsList) {
            if (orRef.entry() instanceof ZipEntry entry) {
                builder.addEntries(entry);
            } else {
                try {
                    builder.addEntries(Objects.requireNonNull(orRef.future()).get());
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        builder.setFormat(1);

        try (var os = Files.newOutputStream(outputZipPartsFile)) {
            // TODO: do we set this to be last used before the parts were written?
            var zipFile = builder.build();
            zipFile.writeTo(os);
        }
    }

    private ZipEntry referenceEntry(FileCompressed file, byte[] zipFileBytes) throws IOException {
        var hash = HashUtils.hash(zipFileBytes, file.offset, file.length, "SHA-256");
        var fileOutPath = invocation.pathFromHash(hash, "dat");
        if (!Files.exists(fileOutPath.getParent())) {
            Files.createDirectories(fileOutPath.getParent());
        }
        try (var fileOutChannel = FileChannel.open(fileOutPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            copyBytes(fileOutChannel, ByteBuffer.wrap(zipFileBytes, file.offset, file.length));
        }

        return ZipEntry.newBuilder().setReferencePart(ReferenceZipPart.newBuilder()
            .setExpectedLength(file.length)
            .setContentHash(hash)
        ).build();
    }

    public void assemble(Path outputZip, Path zipPartsFile) throws IOException {
        ZipFile zipFile;
        try (var is = Files.newInputStream(zipPartsFile)) {
            // should be .binpb file
            zipFile = ZipFile.parseFrom(is);
        }
        if (zipFile.getFormat() != 1) {
            throw new IOException("Unsupported zip part holder format: " + zipFile.getFormat());
        }
        int totalTargetSize = 0;
        for (int i = 0; i < zipFile.getEntriesCount(); i++) {
            var entry = zipFile.getEntries(i);
            switch (entry.getEntryCase()) {
                case SIMPLEPART -> {
                    var part = entry.getSimplePart();
                    totalTargetSize += part.getRawData().size();
                }
                case REFERENCEPART -> {
                    var part = entry.getReferencePart();
                    totalTargetSize += part.getExpectedLength();
                }
                default -> throw new IOException("Unsupported zip entry case: " + entry.getEntryCase());
            }
        }
        try (var outChannel = FileChannel.open(outputZip, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            outChannel.truncate(totalTargetSize);
            int offset = 0;
            var futures = new Future<?>[zipFile.getEntriesCount()];
            for (int i = 0; i < zipFile.getEntriesCount(); i++) {
                var entry = zipFile.getEntries(i);

                var thisOffset = offset;
                switch (entry.getEntryCase()) {
                    case SIMPLEPART -> {
                        var part = entry.getSimplePart();
                        copyBytes(outChannel, thisOffset, part.getRawData().asReadOnlyByteBuffer());
                        offset += part.getRawData().size();
                    }
                    case REFERENCEPART -> {
                        var part = entry.getReferencePart();
                        futures[i] = PARALLEL_EXECUTOR.submit(() -> {
                            try {
                                var contentPath = invocation.pathFromHash(part.getContentHash(), "dat");
                                if (!Files.exists(contentPath)) {
                                    throw new IOException("Missing zip entry content for hash " + part.getContentHash() + " at " + contentPath);
                                }

                                try (var contentChannel = FileChannel.open(contentPath, StandardOpenOption.READ)) {
                                    var totalSize = contentChannel.size();
                                    if (totalSize != part.getExpectedLength()) {
                                        throw new IOException("Mismatched content length for zip entry with hash " + part.getContentHash() + ": expected " + part.getExpectedLength() + ", got " + totalSize);
                                    }
                                    copyChannel(outChannel, thisOffset, contentChannel, 0, totalSize);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });

                        offset += part.getExpectedLength();
                    }
                    default -> throw new IOException("Unsupported zip entry case: " + entry.getEntryCase());
                }
            }
            for (var future : futures) {
                if (future != null) {
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private static void copyBytes(FileChannel outChannel, ByteBuffer bytes) throws IOException {
        copyBytes(outChannel, outChannel.position(), bytes);
    }

    private static void copyBytes(FileChannel outChannel, long outPosition, ByteBuffer bytes) throws IOException {
        int fullSize = bytes.remaining();
        int total = 0;
        int transferred;
        while (total < fullSize && (transferred = outChannel.write(bytes, outPosition)) != 0) {
            total += transferred;
        }
        outChannel.position(outPosition + total);
        if (total < fullSize) {
            throw new IOException("Could not fully copy from byte buffer");
        }
    }

    private static void copyChannel(FileChannel outChannel, SeekableByteChannel inChannel) throws IOException {
        copyChannel(outChannel, outChannel.position(), inChannel, 0, inChannel.size());
    }

    private static void copyChannel(FileChannel outChannel, long outPosition, SeekableByteChannel inChannel, int offset, long length) throws IOException {
        inChannel.position(offset);
        long total = 0;
        long transferred;
        while (total < length && (transferred = outChannel.transferFrom(inChannel, outPosition, length - total)) > 0) {
            total += transferred;
            outPosition += transferred;
        }
        outChannel.position(outPosition);
        if (total < length) {
            throw new IOException("Could not fully copy from channel");
        }
    }
}
