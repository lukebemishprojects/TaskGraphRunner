package dev.lukebemish.taskgraphrunner.runtime.util;

import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

public class NonLoadingClassLoader extends ClassLoader implements Closeable {
    private final @Nullable URLClassLoader toClose;

    public NonLoadingClassLoader(ClassLoader parent) {
        super(parent);
        this.toClose = null;
    }

    public NonLoadingClassLoader(Path[] paths) {
        super(new URLClassLoader(Arrays.stream(paths).map(p -> {
            try {
                return p.toUri().toURL();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toArray(URL[]::new)));
        this.toClose = (URLClassLoader) getParent();
    }

    public NonLoadingClassLoader(URL[] urls) {
        super(new URLClassLoader(urls));
        this.toClose = (URLClassLoader) getParent();
    }

    @Override
    protected Class<?> findClass(String moduleName, String name) {
        throw new UnsupportedOperationException("This classloader proactively protects against class loading");
    }

    @Override
    protected Class<?> findClass(String name) {
        throw new UnsupportedOperationException("This classloader proactively protects against class loading");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) {
        throw new UnsupportedOperationException("This classloader proactively protects against class loading");
    }

    @Override
    public Class<?> loadClass(String name) {
        throw new UnsupportedOperationException("This classloader proactively protects against class loading");
    }

    public boolean hasClass(String name) {
        var resource = name.replace('.', '/') + ".class";
        return getResource(resource) != null;
    }

    public byte @Nullable [] retrieveClass(String name) {
        var resource = name.replace('.', '/') + ".class";
        var url = getResource(resource);
        if (url == null) {
            return null;
        }
        try (var stream = url.openStream()) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (toClose != null) {
            toClose.close();
        }
    }
}
