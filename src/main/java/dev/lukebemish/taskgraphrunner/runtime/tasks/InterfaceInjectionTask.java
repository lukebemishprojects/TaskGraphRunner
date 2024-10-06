package dev.lukebemish.taskgraphrunner.runtime.tasks;

import com.google.gson.reflect.TypeToken;
import dev.lukebemish.taskgraphrunner.model.PathSensitivity;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.Context;
import dev.lukebemish.taskgraphrunner.runtime.Task;
import dev.lukebemish.taskgraphrunner.runtime.TaskInput;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.NonLoadingClassLoader;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class InterfaceInjectionTask extends Task {
    private final TaskInput.HasFileInput input;
    private final TaskInput.FileListInput interfaceInjection;
    private final TaskInput.FileListInput classpath;

    public InterfaceInjectionTask(TaskModel.InterfaceInjection model, WorkItem workItem, Context context) {
        super(model);
        this.input = TaskInput.file("input", model.input, workItem, context, PathSensitivity.NONE);
        this.interfaceInjection = TaskInput.files("interfaceInjection", model.interfaceInjection, workItem, context, PathSensitivity.NONE);

        List<TaskInput.FileListInput> classpathParts = new ArrayList<>();
        for (int i = 0; i < model.classpath.size(); i++) {
            var part = model.classpath.get(i);
            classpathParts.add(TaskInput.files("classpath" + i, part, workItem, context, PathSensitivity.NONE));
        }
        this.classpath = new TaskInput.RecursiveFileListInput("classpath", classpathParts);
    }

    @Override
    public List<TaskInput> inputs() {
        return List.of(input, interfaceInjection);
    }

    @Override
    public Map<String, String> outputTypes() {
        return Map.of(
            "output", "jar",
            "stubs", "jar"
        );
    }

    private record InjectionData(String interfaceBinaryName, @Nullable String signature) {}

    private sealed interface Token {
        enum Simple implements Token {
            OPEN("<"),
            CLOSE(">"),
            EXTENDS("extends"),
            SUPER("super"),
            WILDCARD("?"),
            COMMA(","),
            ARRAY("[]");

            private final String representation;

            Simple(String representation) {
                this.representation = representation;
            }

            @Override
            public String toString() {
                return representation;
            }
        }

        record Name(String name) implements Token {
            @Override
            public String toString() {
                return name;
            }
        }
    }

    private static boolean isInName(int c) {
        return Character.isJavaIdentifierPart(c) || Character.isWhitespace(c) || c == '.';
    }

    private static List<Token> lex(String signature) {
        int index = 0;
        List<Token> tokens = new ArrayList<>();
        var chars = signature.codePoints().toArray();
        while (index < chars.length) {
            var c = chars[index];
            switch (c) {
                case '<' -> tokens.add(Token.Simple.OPEN);
                case '>' -> tokens.add(Token.Simple.CLOSE);
                case '?' -> {
                    tokens.add(Token.Simple.WILDCARD);
                    index++;
                    while (index < chars.length && Character.isWhitespace(chars[index])) {
                        index++;
                    }
                    int extendsLength = "extends".length();
                    int superLength = "super".length();
                    if (index + extendsLength < chars.length && new String(chars, index, extendsLength).equals("extends")) {
                        tokens.add(Token.Simple.EXTENDS);
                        index += extendsLength-1;
                    } else if (index + superLength < chars.length && new String(chars, index, superLength).equals("super")) {
                        tokens.add(Token.Simple.SUPER);
                        index += superLength-1;
                    } else {
                        index--;
                    }
                }
                case ',' -> tokens.add(Token.Simple.COMMA);
                case '[' -> {
                    index++;
                    while (index < chars.length && Character.isWhitespace(chars[index])) {
                        index++;
                    }
                    if (chars[index] != ']') {
                        throw new IllegalArgumentException("Expected `]`, found "+Character.toString(chars[index]));
                    }
                    tokens.add(Token.Simple.ARRAY);
                }
                default -> {
                    if (!Character.isWhitespace(c)) {
                        if (!isInName(c)) {
                            throw new IllegalArgumentException("Invalid character: "+Character.toString(c));
                        }
                        var start = index;
                        while (index < chars.length && isInName(chars[index])) {
                            index++;
                        }
                        var codePoints = signature.substring(start, index).codePoints()
                            .filter(p -> Character.isJavaIdentifierPart(p) || p == '.')
                            .filter(p -> !Character.isIdentifierIgnorable(p))
                            .toArray();
                        String cleanedName = new String(codePoints, 0, codePoints.length);
                        tokens.add(switch (cleanedName) {
                            case "extends" -> Token.Simple.EXTENDS;
                            case "super" -> Token.Simple.SUPER;
                            default -> new Token.Name(cleanedName);
                        });
                        index--;
                    }
                }
            }
            index++;
        }
        return tokens;
    }

    private static int readTypeArguments(StringBuilder signatureBuilder, NonLoadingClassLoader classFinder, ArrayDeque<Token> tokens) {
        // basically just converts
        // https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-TypeArguments
        // to
        // https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-TypeArguments
        int parameters = 0;
        if (tokens.peek() != Token.Simple.OPEN) {
            throw new IllegalArgumentException("Expected `<`, found " + tokens.peek());
        }
        tokens.pop();
        signatureBuilder.append("<");

        Token next;
        while ((next = tokens.peek()) != Token.Simple.CLOSE) {
            if (next == null) {
                throw new IllegalArgumentException("Expected `>`, found "+next);
            }
            readTypeArgumentPart(signatureBuilder, classFinder, tokens);
            parameters++;
            if (tokens.peek() == Token.Simple.COMMA) {
                tokens.pop();
            } else if (tokens.peek() != Token.Simple.CLOSE) {
                throw new IllegalArgumentException("Expected `,` or `>`, found "+tokens.peek());
            }
        }
        signatureBuilder.append(">");
        tokens.pop();
        return parameters;
    }

    private static void readBound(StringBuilder signatureBuilder, NonLoadingClassLoader classFinder, ArrayDeque<Token> tokens) {
        if (tokens.peek() instanceof Token.Name name) {
            tokens.pop();

            StringBuilder baseBinary = new StringBuilder();

            if (tokens.peek() == Token.Simple.OPEN) {
                baseBinary.append("L").append(forName(classFinder, name.name()));
                while (tokens.peek() == Token.Simple.OPEN) {
                    readTypeArguments(baseBinary, classFinder, tokens);
                    if (tokens.peek() instanceof Token.Name innerName && innerName.name().startsWith(".")) {
                        baseBinary.append(innerName.name());
                        tokens.pop();
                    } else {
                        break;
                    }
                }
                baseBinary.append(";");
            } else {
                baseBinary.append(forName(classFinder, name.name()));
            }

            while (tokens.peek() == Token.Simple.ARRAY) {
                tokens.pop();
                baseBinary.insert(0, "[");
            }

            signatureBuilder.append(baseBinary);
        } else {
            throw new IllegalArgumentException("Expected a name, found "+tokens.peek());
        }
    }

    private static void readTypeArgumentPart(StringBuilder signatureBuilder, NonLoadingClassLoader classFinder, ArrayDeque<Token> tokens) {
        if (tokens.peek() == Token.Simple.WILDCARD) {
            tokens.pop();
            if (tokens.peek() == Token.Simple.EXTENDS) {
                tokens.pop();
                signatureBuilder.append("+");
                readTypeArgumentPart(signatureBuilder, classFinder, tokens);
            } else if (tokens.peek() == Token.Simple.SUPER) {
                tokens.pop();
                signatureBuilder.append("-");
                readTypeArgumentPart(signatureBuilder, classFinder, tokens);
            } else {
                signatureBuilder.append("*");
            }
        } else if (tokens.peek() instanceof Token.Name) {
            readBound(signatureBuilder, classFinder, tokens);
        } else {
            throw new IllegalArgumentException("Expected `?` or a name, found "+tokens.peek());
        }
    }

    private static String forName(NonLoadingClassLoader classFinder, String name) {
        return switch (name) {
            case "int" -> "I";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "boolean" -> "Z";
            case "void" -> "V";
            default -> {
                var parts = name.split("\\.");
                for (int i = parts.length; i > 0; i--) {
                    var combined =
                        String.join("/", Arrays.copyOfRange(parts, 0, i))
                            + (i == parts.length ? "" : String.join("$", Arrays.copyOfRange(parts, i, parts.length)));
                    if (classFinder.hasClass(combined)) {
                        yield "L" + combined + ";";
                    }
                }
                if (parts.length == 1) {
                    yield "T" + name + ";";
                }
                var combined = String.join("/", parts);
                yield "L" + combined + ";";
            }
        };
    }

    private record Signature(String signature, int parameters) {}

    private static Signature parseSignature(String binaryName, String signature, NonLoadingClassLoader classFinder) {
        var tokens = new ArrayDeque<>(lex(signature));
        StringBuilder binarySignature = new StringBuilder("L").append(binaryName);

        int parameters = readTypeArguments(binarySignature, classFinder, tokens);

        binarySignature.append(";");
        return new Signature(binarySignature.toString(), parameters);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void run(Context context) {
        var outputJar = context.taskOutputPath(this, "output");
        var stubsJar = context.taskOutputPath(this, "stubs");
        var inputJar = this.input.path(context);

        Map<String, List<InjectionData>> injections = new HashMap<>();
        try (var classFinder = new NonLoadingClassLoader(classpath.paths(context).toArray(Path[]::new));
             var stubsStream = Files.newOutputStream(stubsJar);
             var stubsJarOut = new JarOutputStream(stubsStream)) {
            Set<String> generated = new HashSet<>();
            for (var interfaceInjectionFile : this.interfaceInjection.paths(context)) {

                try (var reader = Files.newBufferedReader(interfaceInjectionFile)) {
                    Map<String, List<String>> interfaceMap = JsonUtils.GSON.fromJson(reader, (TypeToken<Map<String, List<String>>>) TypeToken.getParameterized(Map.class, String.class, TypeToken.getParameterized(List.class, String.class).getType()));
                    for (var entry : interfaceMap.entrySet()) {
                        var target = entry.getKey();
                        for (var injection : entry.getValue()) {
                            if (injection.contains("<")) {
                                var binaryName = injection.substring(0, injection.indexOf('<'));
                                if (!injection.contains(">")) {
                                    throw new IllegalArgumentException("Invalid injection: " + injection);
                                }
                                var signature = injection.substring(injection.indexOf('<'), injection.lastIndexOf('>')+1);
                                var parsedSignature = parseSignature(binaryName, signature, classFinder);
                                injections.computeIfAbsent(target, k -> new ArrayList<>()).add(new InjectionData(binaryName, parsedSignature.signature()));
                                if (!classFinder.hasClass(binaryName) && !generated.contains(binaryName)) {
                                    generated.add(binaryName);
                                    var stubEntry = new ZipEntry(binaryName + ".class");
                                    stubsJarOut.putNextEntry(stubEntry);
                                    var classWriter = new ClassWriter(0);
                                    StringBuilder signatureBuilder = new StringBuilder("<");
                                    for (int i = 0; i < parsedSignature.parameters(); i++) {
                                        int quotient = i / 26;
                                        int remainder = i % 26;
                                        signatureBuilder.append((char)('A' + remainder));
                                        while (quotient > 0) {
                                            remainder = quotient % 26;
                                            quotient /= 26;
                                            signatureBuilder.append((char)('A' + remainder));
                                        }
                                        signatureBuilder.append(":Ljava/lang/Object;");
                                    }
                                    signatureBuilder.append(">Ljava/lang/Object;");
                                    classWriter.visit(
                                        Opcodes.V1_8,
                                        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                                        binaryName,
                                        signatureBuilder.toString(),
                                        "java/lang/Object",
                                        new String[0]
                                    );
                                    classWriter.visitEnd();
                                    stubsJarOut.write(classWriter.toByteArray());
                                    stubsJarOut.closeEntry();
                                }
                            } else {
                                injections.computeIfAbsent(target, k -> new ArrayList<>()).add(new InjectionData(injection, null));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (var inputJarStream = Files.newInputStream(inputJar);
             var outputJarStream = Files.newOutputStream(outputJar);
             var jarIn = new JarInputStream(inputJarStream);
             var jarOut = new JarOutputStream(outputJarStream)) {
            ZipEntry entry;
            // read from input jar directly to output jar
            while ((entry = jarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    jarOut.putNextEntry(new ZipEntry(entry));
                    jarOut.closeEntry();
                } else {
                    var name = entry.getName();
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
                    jarOut.putNextEntry(newEntry);
                    if (name.endsWith(".class") && injections.containsKey(name.substring(0, name.length() - ".class".length()))) {
                        ClassReader reader = new ClassReader(jarIn.readAllBytes());
                        var writer = new ClassWriter(0);
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                                var injectionsForClass = injections.get(name);
                                String[] newInterfaces = new String[interfaces.length + injectionsForClass.size()];
                                System.arraycopy(interfaces, 0, newInterfaces, 0, interfaces.length);
                                for (int i = 0; i < injectionsForClass.size(); i++) {
                                    var injection = injectionsForClass.get(i);
                                    newInterfaces[interfaces.length + i] = injection.interfaceBinaryName;
                                }
                                if (signature != null) {
                                    var builder = new StringBuilder(signature);
                                    for (var injection : injectionsForClass) {
                                        if (injection.signature != null) {
                                            builder.append(injection.signature);
                                        } else {
                                            builder.append("L").append(injection.interfaceBinaryName).append(";");
                                        }
                                    }
                                    signature = builder.toString();
                                } else {
                                    boolean needsSignature = false;
                                    for (var injection : injectionsForClass) {
                                        if (injection.signature != null) {
                                            needsSignature = true;
                                            break;
                                        }
                                    }
                                    if (needsSignature) {
                                        var builder = new StringBuilder("<>L").append(superName).append(";");
                                        for (var iface : interfaces) {
                                            builder.append("L").append(iface).append(";");
                                        }
                                        for (var injection : injectionsForClass) {
                                            if (injection.signature != null) {
                                                builder.append(injection.signature);
                                            } else {
                                                builder.append("L").append(injection.interfaceBinaryName).append(";");
                                            }
                                        }
                                        signature = builder.toString();
                                    }
                                }
                                super.visit(version, access, name, signature, superName, newInterfaces);
                            }
                        }, 0);
                        jarOut.write(writer.toByteArray());
                    } else {
                        jarIn.transferTo(jarOut);
                    }
                    jarOut.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
