package dev.lukebemish.taskgraphrunner.runtime.util;

import dev.lukebemish.taskgraphrunner.runtime.RecordedInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtils {
    private HashUtils() {}

    public static void hash(Path path, RecordedInput.ByteConsumer digest) throws IOException {
        try (var is = Files.newInputStream(path)) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String hash(Path path, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        hash(path, RecordedInput.ByteConsumer.of(digest));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hash(Path path) throws IOException {
        return hash(path, "MD5");
    }
}
