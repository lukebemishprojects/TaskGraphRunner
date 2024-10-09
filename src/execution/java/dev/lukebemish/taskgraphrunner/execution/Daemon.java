package dev.lukebemish.taskgraphrunner.execution;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.jar.JarFile;

public class Daemon implements AutoCloseable {
    private final ServerSocket socket;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("TaskGraphRunnerExecutionDaemon-",1).factory());

    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;
    private static final InputStream IN = System.in;

    static {
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setIn(InputStream.nullInputStream());
    }

    public Daemon() throws IOException {
        this.socket = new ServerSocket(0);
    }

    public static void main(String[] args) {
        try (var daemon = new Daemon()) {
            daemon.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void run() throws IOException{
        OUT.println(socket.getLocalPort());
        var socket = this.socket.accept();
        var input = new DataInputStream(socket.getInputStream());
        var os = new DataOutputStream(socket.getOutputStream());
        var output = new Output(os);
        while (true) {
            int id = input.readInt();
            if (id == -1) {
                break;
            }
            int mode = input.readInt();
            switch (mode) {
                case 0 -> {
                    // Launch with classpath
                    String classpath = input.readUTF();
                    String mainClass = input.readUTF();
                    String logFile = input.readUTF();
                    String[] args = new String[input.readInt()];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = input.readUTF();
                    }
                    executor.submit(() -> {
                        int result = 0;
                        try {
                            result = launch(classpath, mainClass, args, logFile);
                        } catch (Throwable t) {
                            result = 1;
                        }
                        try {
                            output.write(id, result);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                case 1 -> {
                    // Launch with jar
                    String jar = input.readUTF();
                    String logFile = input.readUTF();
                    String[] args = new String[input.readInt()];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = input.readUTF();
                    }
                    executor.submit(() -> {
                        int result = 0;
                        try {
                            result = launch(jar, args, logFile);
                        } catch (Throwable t) {
                            result = 1;
                        }
                        try {
                            output.write(id, result);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        }
    }

    private record Output(DataOutputStream output) {
        synchronized void write(int id, int code) throws IOException {
            output.writeInt(id);
            output.writeInt(code);
            output.flush();
        }
    }

    @Override
    public void close() {

    }

    private record Target(URL[] urls, String mainClass) {}

    private static int execute(String[] args, Supplier<Target> targetSupplier, String logFile) {
        try (var fileOutputStream = new FileOutputStream(logFile, true);
             var printStream = new PrintStream(fileOutputStream)) {
            try {
                var target = targetSupplier.get();
                var urls = target.urls();
                var mainClass = target.mainClass();
                var exitScopedClassLoader = new ExitScope();
                try (var urlClassLoader = new URLClassLoader(urls, Daemon.class.getClassLoader())) {
                    SystemStreams.OUT.set(printStream);
                    SystemStreams.ERR.set(printStream);
                    ExitScope.SCOPE.set(exitScopedClassLoader);
                    Thread.currentThread().setContextClassLoader(urlClassLoader);
                    try {
                        var main = Class.forName(mainClass, false, urlClassLoader);
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
                        Thread.currentThread().setContextClassLoader(null);
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace(printStream);
                return 1;
            }
        } catch (IOException e) {
            e.printStackTrace(ERR);
            return 1;
        }
        return 0;
    }

    private static int launch(String classpath, String mainClass, String[] args, String logFile) {
        return execute(args, () -> {
            var urlStrings = classpath.split(":");
            var urls = new URL[urlStrings.length];
            for (int i = 0; i < urlStrings.length; i++) {
                try {
                    urls[i] = Paths.get(urlStrings[i]).toUri().toURL();
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }
            return new Target(urls, mainClass);
        }, logFile);
    }

    private static int launch(String jar, String[] args, String logFile) {
        return execute(args, () -> {
            try {
                var url = Paths.get(jar).toUri().toURL();
                String mainClass;
                try (var jarFile = new JarFile(url.getFile())) {
                    var manifest = jarFile.getManifest();
                    mainClass = manifest.getMainAttributes().getValue("Main-Class");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (mainClass == null) {
                    throw new RuntimeException("No Main-Class attribute in manifest");
                }
                return new Target(new URL[]{url}, mainClass);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }, logFile);
    }
}
