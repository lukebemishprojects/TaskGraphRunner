package dev.lukebemish.taskgraphrunner.runtime.util;

import dev.lukebemish.taskgraphrunner.runtime.RecordedInput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class HashUtils {
    private HashUtils() {}

    private static final Queue<Path> cachedHashPaths = new ConcurrentLinkedDeque<>();
    private static final int pathsToCache = 1024;
    private static final Map<Path, CacheResult> cachedHashes = new ConcurrentHashMap<>();

    private record CacheResult(byte[] result, FileTime lastModified) {}

    public static void hash(Path path, RecordedInput.ByteConsumer digest) {
        hash(path, digest, "MD5");
    }

    public static void hash(Path path, RecordedInput.ByteConsumer finalDigest, String algorithm) {
        cachedHashPaths.add(path);
        if (cachedHashPaths.size() > pathsToCache) {
            var removedPath = cachedHashPaths.poll();
            cachedHashes.remove(removedPath);
        }
        var existingHash = cachedHashes.get(path);
        if (existingHash != null) {
            try {
                if (Files.getLastModifiedTime(path).compareTo(existingHash.lastModified()) != 0) {
                    cachedHashes.remove(path);
                    hash(path, finalDigest, algorithm);
                    return;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            finalDigest.update(existingHash.result());
            return;
        }

        try (var is = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            is.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            var hash = digest.digest();
            var lastModified = Files.getLastModifiedTime(path);
            cachedHashes.put(path, new CacheResult(hash, lastModified));
            finalDigest.update(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static void hash(String key, RecordedInput.ByteConsumer digest) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(Path path, String algorithm) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        hash(path, RecordedInput.ByteConsumer.of(output), algorithm);
        return HexFormat.of().formatHex(output.toByteArray());
    }

    public static String hash(String key, String algorithm) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        hash(key, RecordedInput.ByteConsumer.of(digest));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hash(Path path) throws IOException {
        return hash(path, "MD5");
    }

    public static String hash(String key) {
        return hash(key, "MD5");
    }
}
