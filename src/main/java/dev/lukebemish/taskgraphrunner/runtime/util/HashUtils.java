package dev.lukebemish.taskgraphrunner.runtime.util;

import dev.lukebemish.taskgraphrunner.runtime.RecordedInput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int pathsToCache = 256;
    private static final Map<Path, byte[]> cachedHashes = new ConcurrentHashMap<>();

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
            finalDigest.update(existingHash);
            return;
        }

        try (var is = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[2048];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            var hash = digest.digest();
            cachedHashes.put(path, hash);
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
