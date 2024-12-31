package dev.lukebemish.taskgraphrunner.runtime.execution;

import dev.lukebemish.forkedtaskexecutor.ForkedTaskExecutor;
import dev.lukebemish.forkedtaskexecutor.ForkedTaskExecutorSpec;
import dev.lukebemish.taskgraphrunner.runtime.AgentInjector;
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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class ToolDaemonExecutor implements AutoCloseable {
    public static void execute(Path jar, Path logFile, String[] args, Context context, boolean classpathScoped) {
        Path transformedJar;
        try {
            transformedJar = transform(jar, context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String mainClass;
        try (var jarFile = new JarInputStream(Files.newInputStream(transformedJar))) {
            var manifest = jarFile.getManifest();
            mainClass = manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (mainClass == null) {
            throw new RuntimeException("No Main-Class attribute in manifest");
        }
        execute(List.of(transformedJar), mainClass, logFile, args, context, classpathScoped);
    }

    public static void execute(Collection<Path> classpath, String mainClass, Path logFile, String[] args, Context context, boolean classpathScoped) {
        var transformedClasspath = classpath.stream().map(p -> {
            try {
                return transform(p.toAbsolutePath(), context).toAbsolutePath();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toArray(Path[]::new);
        var transformedClasspathString = Arrays.stream(transformedClasspath).map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        (classpathScoped ? getInstance(transformedClasspath) : getInstance()).execute(classpathScoped ? "" : transformedClasspathString, mainClass, logFile, args);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolDaemonExecutor.class);
    private static ToolDaemonExecutor INSTANCE;
    private static final Map<String, ToolDaemonExecutor> CLASSPATH_INSTANCES = new ConcurrentHashMap<>();

    private final ForkedTaskExecutor executor;
    private final Runnable onRemoval;

    private ToolDaemonExecutor(Runnable onRemoval) throws IOException {
        this(new Path[0], onRemoval);
    }

    private ToolDaemonExecutor(Path[] classpath, Runnable onRemoval) throws IOException {
        this.onRemoval = onRemoval;
        var workingDirectory = Files.createTempDirectory("taskgraphrunner");
        var tempFile = workingDirectory.resolve("execution-daemon.jar");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                FileUtils.deleteRecursively(workingDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
        try (var stream = ToolDaemonExecutor.class.getResourceAsStream("/execution-daemon.jar")) {
            Files.copy(Objects.requireNonNull(stream, "Could not find bundled tool execution daemon"), tempFile);
        }

        List<String> fullClasspath = new ArrayList<>();
        fullClasspath.add(tempFile.toAbsolutePath().toString());
        for (Path path : classpath) {
            fullClasspath.add(path.toAbsolutePath().toString());
        }
        var classpathFile = workingDirectory.resolve("classpath.txt");
        try (var writer = Files.newBufferedWriter(classpathFile, StandardCharsets.UTF_8)) {
            writer.write(String.join(File.pathSeparator, fullClasspath));
            writer.newLine();
        }

        var spec = ForkedTaskExecutorSpec.builder()
            .javaExecutable(ProcessHandle.current()
                .info()
                .command()
                .orElseThrow())
            .addJvmOption(AgentInjector.makeArg())
            .addJvmOption("-cp")
            .addJvmOption("@"+ classpathFile.toAbsolutePath())
            .taskClass("dev.lukebemish.taskgraphrunner.execution.ToolTask")
            .build();

        this.executor = new ForkedTaskExecutor(spec);
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
            }
            CLASSPATH_INSTANCES.values().forEach(ToolDaemonExecutor::close);
            CLASSPATH_INSTANCES.clear();
        }));
    }

    @Override
    public void close() {
        onRemoval.run();
        executor.close();
    }

    private static synchronized ToolDaemonExecutor getInstance(Path[] classpath) {
        var key = String.join(File.pathSeparator, Arrays.stream(classpath).map(it -> it.toAbsolutePath().toString()).toArray(CharSequence[]::new));
        return CLASSPATH_INSTANCES.computeIfAbsent(key, k -> {
            try {
                return new ToolDaemonExecutor(classpath, () -> CLASSPATH_INSTANCES.remove(key));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static synchronized ToolDaemonExecutor getInstance() {
        if (INSTANCE == null) {
            try {
                INSTANCE = new ToolDaemonExecutor(() -> INSTANCE = null);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return INSTANCE;
    }

    private static void writeString(DataOutputStream output, String string) throws IOException {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private void execute(String classpath, String mainClass, Path logFile, String[] args) {
        var output = new ByteArrayOutputStream();
        try (var os = new DataOutputStream(output)) {
            writeString(os, classpath);
            writeString(os, mainClass);
            writeString(os, logFile.toAbsolutePath().toString());
            os.writeInt(args.length);
            for (String arg : args) {
                writeString(os, arg);
            }
            var status = ByteBuffer.wrap(executor.submit(output.toByteArray())).getInt();
            if (status != 0) {
                throw new RuntimeException("Tool failed with exit code " + status);
            }
        } catch (Throwable e) {
            logError(logFile);
            throw new RuntimeException(e);
        }
    }

    private static void logError(Path logFile) {
        if (Files.exists(logFile)) {
            LOGGER.error("Process failed; see log file at {}", logFile.toAbsolutePath());
        } else {
            LOGGER.error("Process failed, but no log file found at {}", logFile.toAbsolutePath());
        }
    }

    private static final int TRANSFORM_VERSION = 0;

    private static Path transform(Path jar, Context context) throws IOException {
        var now = FileTime.from(Instant.now());
        var fileHash = HashUtils.hash(jar);
        var outJarPath = context.transformCachePath(TRANSFORM_VERSION).resolve(jar.getFileName().toString()).resolve(fileHash + ".jar");
        var outJarMarker = context.transformCachePath(TRANSFORM_VERSION).resolve(jar.getFileName().toString()).resolve(fileHash + ".jar.marker");
        var nameHash = HashUtils.hash(jar.getFileName().toString());
        try (var ignored = context.lockManager().lock("transform."+TRANSFORM_VERSION+"."+nameHash+"."+fileHash)) {
            Files.createDirectories(outJarPath.getParent());
            if (Files.exists(outJarMarker)) {
                FileUtils.setLastAccessedTime(outJarMarker, now);
                if (Files.exists(outJarMarker)) {
                    return outJarPath;
                }
            }
            try (var out = Files.newOutputStream(outJarPath);
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
                                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/lukebemish/taskgraphrunner/execution/SystemStreams", name, "()" + descriptor, false);
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
        }
        return outJarPath;
    }
}
