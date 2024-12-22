package dev.lukebemish.taskgraphrunner.execution;

import dev.lukebemish.forkedtaskexecutor.runner.Task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Supplier;

public class ToolTask implements Task {
    private PrintStream err;

    public ToolTask(String[] args) {}

    @Override
    public byte[] run(byte[] bytes) throws Exception {
        int result = 0;
        try (var is = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String classpath = new String(is.readNBytes(is.readInt()), StandardCharsets.UTF_8);
            String mainClass = new String(is.readNBytes(is.readInt()), StandardCharsets.UTF_8);
            String logFile = new String(is.readNBytes(is.readInt()), StandardCharsets.UTF_8);
            String[] args = new String[is.readInt()];
            for (int i = 0; i < args.length; i++) {
                args[i] = new String(is.readNBytes(is.readInt()), StandardCharsets.UTF_8);
            }
            result = launch(classpath, mainClass, args, logFile);
        } catch (Throwable t) {
            result = -1;
        }
        var outBytes = new ByteArrayOutputStream();
        try (var os = new DataOutputStream(outBytes)) {
            os.writeInt(result);
        }
        return outBytes.toByteArray();
    }

    @Override
    public PrintStream replaceSystemOut(PrintStream out) {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    @Override
    public InputStream replaceSystemIn(InputStream in) {
        return InputStream.nullInputStream();
    }

    @Override
    public PrintStream replaceSystemErr(PrintStream err) {
        this.err = err;
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private record Target(URL[] urls, String mainClass) {}

    private int execute(String[] args, Supplier<Target> targetSupplier, String logFile) {
        try (var fileOutputStream = new FileOutputStream(logFile, true);
             var printStream = new PrintStream(fileOutputStream)) {
            try {
                var target = targetSupplier.get();
                var urls = target.urls();
                var mainClass = target.mainClass();
                var exitScopedClassLoader = new ExitScope();
                try (var urlClassLoader = (urls.length != 0) ? new URLClassLoader(urls, ToolTask.class.getClassLoader()) : null) {
                    SystemStreams.OUT.set(printStream);
                    SystemStreams.ERR.set(printStream);
                    ExitScope.SCOPE.set(exitScopedClassLoader);
                    if (urls.length == 0) {
                        Thread.currentThread().setContextClassLoader(ToolTask.class.getClassLoader());
                    } else {
                        Thread.currentThread().setContextClassLoader(urlClassLoader);
                    }
                    try {
                        var main = Class.forName(mainClass, false, urlClassLoader == null ? ToolTask.class.getClassLoader() : urlClassLoader);
                        var method = main.getMethod("main", String[].class);
                        method.invoke(null, (Object) args);
                    } catch (Throwable t) {
                        Throwable cause = t;
                        while (!(cause instanceof SystemExit) && cause.getCause() != null) {
                            cause = t.getCause();
                        }
                        if (cause instanceof SystemExit systemExit) {
                            return systemExit.status();
                        } else if (exitScopedClassLoader.exitStatus() != 0) {
                            return exitScopedClassLoader.exitStatus();
                        } else {
                            t.printStackTrace(printStream);
                            return 1;
                        }
                    } finally {
                        Thread.currentThread().setContextClassLoader(ToolTask.class.getClassLoader());
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace(printStream);
                return 1;
            }
        } catch (IOException e) {
            e.printStackTrace(err);
            return 1;
        }
        return 0;
    }

    private int launch(String classpath, String mainClass, String[] args, String logFile) {
        return execute(args, () -> {
            var urlStrings = Arrays.stream(classpath.split(File.pathSeparator)).filter(s -> !s.isBlank()).toList();
            var urls = new URL[urlStrings.size()];
            for (int i = 0; i < urlStrings.size(); i++) {
                try {
                    urls[i] = Paths.get(urlStrings.get(i)).toUri().toURL();
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }
            return new Target(urls, mainClass);
        }, logFile);
    }
}
