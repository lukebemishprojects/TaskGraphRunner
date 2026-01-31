package dev.lukebemish.taskgraphrunner.runtime.zips;

import com.google.protobuf.ByteString;
import dev.lukebemish.taskgraphrunner.runtime.Invocation;
import dev.lukebemish.taskgraphrunner.runtime.util.HashUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public class PiecewiseZips {
    private final Invocation invocation;

    public PiecewiseZips(Invocation invocation) {
        this.invocation = invocation;
    }

    public void disassemble(Path inputZip, Path outputZipPartsFile) throws IOException {
        var builder = ZipFile.newBuilder();
        byte[] zipFileBytes = Files.readAllBytes(inputZip);
        var fileSize = zipFileBytes.length;

        // TODO: do we risk streaming it and assume we get "nice" jars? And then validate at the end?

        record FileCompressed(int offset, int length) implements Comparable<FileCompressed> {
            @Override
            public int compareTo(FileCompressed o) {
                return Integer.compare(this.offset, o.offset);
            }
        }
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
                    // TODO: should this cutoff be configurable?
                    if (compressedSize < 1000) {
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
        for (var file : files) {
            if (file.offset > head) {
                builder.addEntries(ZipEntry.newBuilder().setSimplePart(SimpleZipPart.newBuilder()
                    .setRawData(ByteString.copyFrom(zipFileBytes, head, file.offset - head))
                    .build()));
                head = file.offset;
            }
            if (file.offset == head) {
                byte[] fileBytes = new byte[file.length];
                System.arraycopy(zipFileBytes, file.offset, fileBytes, 0, fileBytes.length);
                var hash = HashUtils.hash(fileBytes, "SHA-256");
                var fileOutPath = invocation.pathFromHash(hash, "dat");
                if (!Files.exists(fileOutPath.getParent())) {
                    Files.createDirectories(fileOutPath.getParent());
                }
                Files.write(fileOutPath, fileBytes);
                builder.addEntries(ZipEntry.newBuilder().setReferencePart(ReferenceZipPart.newBuilder()
                    .setExpectedLength(file.length)
                    .setContentHash(hash)
                    .build()));
                head += file.length;
            } else {
                throw new IOException("Overlapping local files in zip disassembly");
            }
        }
        if (head < fileSize) {
            builder.addEntries(ZipEntry.newBuilder().setSimplePart(SimpleZipPart.newBuilder()
                .setRawData(ByteString.copyFrom(zipFileBytes, head, fileSize - head))
                .build()));
        }

        builder.setFormat(1);

        try (var os = Files.newOutputStream(outputZipPartsFile)) {
            var zipFile = builder.build();
            zipFile.writeTo(os);
        }
    }

    public void assemble(Path outputZip, Path zipPartsFile) throws IOException {
        // TODO: try without NIO, see if it's faster? Profile?
        ZipFile zipFile;
        try (var is = Files.newInputStream(zipPartsFile)) {
            // should be .binpb file
            zipFile = ZipFile.parseFrom(is);
        }
        if (zipFile.getFormat() != 1) {
            throw new IOException("Unsupported zip part holder format: " + zipFile.getFormat());
        }
        try (var os = new BufferedOutputStream(Files.newOutputStream(outputZip));
             var channel = Channels.newChannel(os)) {
            for (int i = 0; i < zipFile.getEntriesCount(); i++) {
                var entry = zipFile.getEntries(i);

                switch (entry.getEntryCase()) {
                    case SIMPLEPART -> {
                        var part = entry.getSimplePart();
                        channel.write(part.getRawData().asReadOnlyByteBuffer());
                    }
                    case REFERENCEPART -> {
                        var part = entry.getReferencePart();
                        var contentPath = invocation.pathFromHash(part.getContentHash(), "dat");
                        if (!Files.exists(contentPath)) {
                            throw new IOException("Missing zip entry content for hash " + part.getContentHash() + " at " + contentPath);
                        }

                        try (var is = new BufferedInputStream(Files.newInputStream(contentPath))) {
                            long written = is.transferTo(os);
                            if (written != part.getExpectedLength()) {
                                throw new IOException("Mismatched zip entry content size for hash " + part.getContentHash() + ": expected " + part.getExpectedLength() + ", got " + written);
                            }
                        }
                    }
                    default -> throw new IOException("Unsupported zip entry case: " + entry.getEntryCase());
                }
            }
        }
    }
}
