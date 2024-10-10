package dev.lukebemish.taskgraphrunner.runtime.execution;

import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.util.FileUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.HashUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class DaemonExecutor implements AutoCloseable {
    private final Process process;
    private final Socket socket;
    private final ResultListener listener;

    private DaemonExecutor() throws IOException {
        var workingDirectory = Files.createTempDirectory("taskgraphrunner");
        var tempFile = workingDirectory.resolve("execution-daemon.jar");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                FileUtils.deleteRecursively(workingDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
        try (var stream = DaemonExecutor.class.getResourceAsStream("/execution-daemon.jar")) {
            Files.copy(Objects.requireNonNull(stream, "Could not find bundled tool execution daemon"), tempFile);
        }

        var builder = new ProcessBuilder();
        var javaExecutablePath = ProcessHandle.current()
            .info()
            .command()
            .orElseThrow();
        builder.command(javaExecutablePath, "-jar", tempFile.toAbsolutePath().toString());
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);

        try {
            this.process = builder.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        CompletableFuture<String> socketPort = new CompletableFuture<>();
        var thread = new StreamWrapper(process.getInputStream(), socketPort);
        new Thread(() -> {
            try {
                InputStreamReader reader = new InputStreamReader(process.getErrorStream());
                BufferedReader bufferedReader = new BufferedReader(reader);
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).start();
        thread.start();
        try {
            String socketPortString = socketPort.get(4000, TimeUnit.MILLISECONDS);
            int port = Integer.parseInt(socketPortString);
            this.socket = new Socket(InetAddress.getLoopbackAddress(), port);

            this.listener = new ResultListener(socket);
            this.listener.start();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class StreamWrapper extends Thread {
        private final InputStream stream;
        private final CompletableFuture<String> socketPort;

        private StreamWrapper(InputStream stream, CompletableFuture<String> socketPort) {
            this.stream = stream;
            this.socketPort = socketPort;
            this.setUncaughtExceptionHandler((t, e) -> {
                socketPort.completeExceptionally(e);
                StreamWrapper.this.getThreadGroup().uncaughtException(t, e);
            });
        }

        @Override
        public void run() {
            try {
                var reader = new BufferedReader(new InputStreamReader(stream));
                socketPort.complete(reader.readLine());
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static final class ResultListener extends Thread {
        private final Map<Integer, CompletableFuture<?>> results = new ConcurrentHashMap<>();
        private final Socket socket;
        private final DataOutputStream output;

        private ResultListener(Socket socket) throws IOException {
            this.socket = socket;
            output = new DataOutputStream(socket.getOutputStream());
            this.setUncaughtExceptionHandler((t, e) -> {
                try {
                    shutdown(e);
                } catch (IOException ex) {
                    var exception = new UncheckedIOException(ex);
                    exception.addSuppressed(e);
                    ResultListener.this.getThreadGroup().uncaughtException(t, exception);
                }
                ResultListener.this.getThreadGroup().uncaughtException(t, e);
            });
        }

        private synchronized Future<?> submit(int id, IoConsumer<DataOutputStream> taskWriter) throws IOException {
            if (closed) {
                throw new IOException("Listener is closed");
            }
            var out = results.computeIfAbsent(id, i -> new CompletableFuture<>());
            output.writeInt(id);
            taskWriter.accept(output);
            output.flush();
            return out;
        }

        private volatile boolean closed = false;

        private synchronized void beginClose(Throwable e) throws IOException {
            if (this.closed) return;
            this.closed = true;
            for (var future : results.values()) {
                future.completeExceptionally(e);
            }
            results.clear();

            socket.shutdownInput();
        }

        private void finishClose() throws IOException {
            output.writeInt(-1);
            socket.close();
        }

        public void shutdown() throws IOException {
            shutdown(new IOException("Execution was interrupted"));
        }

        private void shutdown(Throwable t) throws IOException {
            this.beginClose(t);
            try {
                this.join();
            } catch (InterruptedException e) {
                // continue, it's fine
            }
            this.finishClose();
        }

        @Override
        public void run() {
            try {
                if (!closed) {
                    var input = new DataInputStream(socket.getInputStream());
                    while (!closed) {
                        int id = input.readInt();
                        int status = input.readInt();
                        if (status == 0) {
                            var future = results.remove(id);
                            if (future != null) {
                                future.complete(null);
                            }
                        } else {
                            var future = results.remove(id);
                            if (future != null) {
                                var exception = new RuntimeException("Tool failed with exit code " + status);
                                future.completeExceptionally(exception);
                            }
                        }
                    }
                }
            } catch (EOFException ignored) {

            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public synchronized void close() {
        List<Exception> suppressed = new ArrayList<>();
        if (listener != null) {
            try {
                listener.shutdown();
            } catch (Exception e) {
                suppressed.add(e);
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                suppressed.add(e);
            }
        }
        if (process != null) {
            try {
                process.destroy();
                process.waitFor();
            } catch (Exception e) {
                suppressed.add(e);
            }
        }
        if (!suppressed.isEmpty()) {
            var exception = new IOException("Failed to close resources");
            suppressed.forEach(exception::addSuppressed);
            throw new UncheckedIOException(exception);
        }
    }

    private final AtomicInteger id = new AtomicInteger();

    private interface IoConsumer<T> {
        void accept(T t) throws IOException;
    }

    private void execute(String classpath, String mainClass, Path logFile, String[] args) {
        var nextId = id.getAndIncrement();
        try {
            listener.submit(nextId, output -> {
                output.writeInt(0);
                output.writeUTF(classpath);
                output.writeUTF(mainClass);
                output.writeUTF(logFile.toAbsolutePath().toString());
                output.writeInt(args.length);
                for (String arg : args) {
                    output.writeUTF(arg);
                }
            }).get();
        } catch (IOException | ExecutionException | InterruptedException e) {
            logError(logFile);
            throw new RuntimeException(e);
        }
    }

    private static void logError(Path logFile) {
        if (Files.exists(logFile)) {
            LOGGER.error("Process failed; see log file at {}", logFile.toAbsolutePath());
        }
    }

    private void execute(Path jar, Path logFile, String[] args) {
        var nextId = id.getAndIncrement();
        try {
            listener.submit(nextId, output -> {
                output.writeInt(1);
                output.writeUTF(jar.toAbsolutePath().toString());
                output.writeUTF(logFile.toAbsolutePath().toString());
                output.writeInt(args.length);
                for (String arg : args) {
                    output.writeUTF(arg);
                }
            }).get();
        } catch (IOException | ExecutionException | InterruptedException e) {
            logError(logFile);
            throw new RuntimeException(e);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DaemonExecutor.class);
    private static DaemonExecutor INSTANCE;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (INSTANCE != null) {
                INSTANCE.close();
            }
        }));
    }

    private static synchronized DaemonExecutor getInstance() {
        if (INSTANCE == null) {
            try {
                INSTANCE = new DaemonExecutor();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (!INSTANCE.process.isAlive()) {
            LOGGER.warn("Tool execution daemon has died; starting a new one");
            INSTANCE.close();
            try {
                INSTANCE = new DaemonExecutor();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return INSTANCE;
    }

    private static final int TRANSFORM_VERSION = 0;

    private static Path transform(Path jar, Context context) throws IOException {
        var now = FileTime.from(Instant.now());
        var fileHash = HashUtils.hash(jar);
        var outJarPath = context.transformCachePath(TRANSFORM_VERSION).resolve(jar.getFileName().toString()).resolve(fileHash + ".jar");
        var outJarMarker = context.transformCachePath(TRANSFORM_VERSION).resolve(jar.getFileName().toString()).resolve(fileHash + ".jar.marker");
        Files.createDirectories(outJarPath.getParent());
        if (Files.exists(outJarMarker)) {
            FileUtils.setLastAccessedTime(outJarMarker, now);
            if (Files.exists(outJarMarker)) {
                return outJarPath;
            }
        }
        var nameHash = HashUtils.hash(jar.getFileName().toString());
        try (var ignored = context.lockManager().lock("transform."+TRANSFORM_VERSION+"."+nameHash+"."+fileHash);
             var out = Files.newOutputStream(outJarPath);
             var in = Files.newInputStream(jar);
             var inJar = new JarInputStream(in);
             var outJar = new JarOutputStream(out, inJar.getManifest())) {

            ZipEntry entry;
            while ((entry = inJar.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".class")) {
                    outJar.putNextEntry(entry);
                    inJar.transferTo(outJar);
                    outJar.closeEntry();
                } else {
                    var newEntry = new ZipEntry(entry.getName());
                    if (entry.getComment() != null) {
                        newEntry.setComment(entry.getComment());
                    }
                    if (entry.getCreationTime() != null) {
                        newEntry.setCreationTime(entry.getCreationTime());
                    }
                    if (entry.getLastModifiedTime() != null) {
                        newEntry.setLastModifiedTime(entry.getLastModifiedTime());
                    }
                    if (entry.getLastAccessTime() != null) {
                        newEntry.setLastAccessTime(entry.getLastAccessTime());
                    }
                    if (entry.getExtra() != null) {
                        newEntry.setExtra(entry.getExtra());
                    }
                    outJar.putNextEntry(newEntry);

                    ClassReader reader = new ClassReader(inJar);
                    ClassWriter writer = new ClassWriter(0);
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                        String className;

                        @Override
                        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                            className = name;
                            super.visit(version, access, name, signature, superName, interfaces);
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                    if ("java/lang/System".equals(owner) && opcode == Opcodes.GETSTATIC) {
                                        switch (name) {
                                            case "out", "err", "in" -> {
                                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/lukebemish/taskgraphrunner/execution/SystemStreams", name, "()"+descriptor, false);
                                                return;
                                            }
                                        }
                                    }
                                    super.visitFieldInsn(opcode, owner, name, descriptor);
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                    if ("java/lang/System".equals(owner)) {
                                        if (name.equals("exit") && opcode == Opcodes.INVOKESTATIC) {
                                            super.visitMethodInsn(opcode, "dev/lukebemish/taskgraphrunner/execution/ExitScope", name, descriptor, false);
                                            return;
                                        }
                                    } else if ("java/lang/Runtime".equals(owner)) {
                                        if (name.equals("exit") && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)) {
                                            super.visitInsn(Opcodes.POP);
                                            super.visitMethodInsn(opcode, "dev/lukebemish/taskgraphrunner/execution/ExitScope", name, descriptor, false);
                                            return;
                                        }
                                    }
                                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                }
                            };
                        }
                    };
                    reader.accept(visitor, 0);
                    outJar.write(writer.toByteArray());
                    outJar.closeEntry();
                }
            }

            Files.createFile(outJarMarker);
            FileUtils.setLastAccessedTime(outJarMarker, now);
        }
        return outJarPath;
    }

    public static void execute(Path jar, Path logFile, String[] args, Context context) {
        Path transformedJar;
        try {
            transformedJar = transform(jar, context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        getInstance().execute(transformedJar, logFile, args);
    }

    public static void execute(Collection<Path> classpath, String mainClass, Path logFile, String[] args, Context context) {
        var transformedClasspath = classpath.stream().map(p -> {
            try {
                return transform(p.toAbsolutePath(), context).toAbsolutePath();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        getInstance().execute(transformedClasspath, mainClass, logFile, args);
    }
}
