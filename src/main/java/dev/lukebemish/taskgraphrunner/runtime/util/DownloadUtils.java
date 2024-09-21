package dev.lukebemish.taskgraphrunner.runtime.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DownloadUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadUtils.class);

    private static final String USER_AGENT = "TaskGraphRunner/"+ DownloadUtils.class.getPackage().getImplementationVersion();

    private DownloadUtils() {}

    public static boolean download(Spec spec, Path target) throws IOException {
        var uri = spec.uri();
        LOGGER.info("Downloading "+uri+" -> "+target.toAbsolutePath());
        var checksum = spec.checksum();
        var checksumAlgorithm = spec.checksumAlgorithm();
        if (checksum != null && checksumAlgorithm != null && Files.exists(target)) {
            var existingHash = HashUtils.hash(target, checksumAlgorithm);
            if (checksum.equalsIgnoreCase(existingHash)) {
                LOGGER.info("Checksum for "+target+" matches, skipping download");
                return false;
            }
        }

        var partial = target.resolveSibling(target.getFileName()+"."+Math.random()+".partial");
        Files.createDirectories(partial.getParent());

        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);

        if (Files.exists(target)) {
            long lastModified = Files.getLastModifiedTime(target).toMillis();
            if (lastModified != 0) {
                connection.setIfModifiedSince(lastModified);
            }
        }

        connection.connect();

        if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
            LOGGER.info("File not modified, skipping download");
            return false;
        }

        try (var input = connection.getInputStream()) {
            Files.copy(input, partial);

            if (spec.size() != -1 && Files.size(partial) != spec.size()) {
                throw new IOException("Downloaded file size does not match expected size (found "+Files.size(partial)+", expected "+spec.size()+")");
            }

            if (checksum != null && checksumAlgorithm != null) {
                var hash = HashUtils.hash(partial, checksumAlgorithm);
                if (!checksum.equalsIgnoreCase(hash)) {
                    throw new IOException("Downloaded file checksum does not match expected checksum (found "+hash+", expected "+checksum+")");
                }
            }

            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
        return true;
    }

    public sealed interface Spec {
        URI uri();

        String checksum();
        String checksumAlgorithm();
        long size();

        record Simple(URI uri) implements Spec {
            @Override
            public String checksum() {
                return null;
            }

            @Override
            public String checksumAlgorithm() {
                return null;
            }

            @Override
            public long size() {
                return -1;
            }
        }

        record Checksum(URI uri, String checksum, String checksumAlgorithm) implements Spec {
            @Override
            public long size() {
                return -1;
            }
        }

        record ChecksumAndSize(URI uri, String checksum, String checksumAlgorithm, long size) implements Spec {}
    }
}
