package dev.lukebemish.taskgraphrunner.signatures;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

final class TypeSignatureImpl {
    private TypeSignatureImpl() {}

    static TypeSignature parseNeoInjection(String injection, ClassFinder classFinder) {
        if (injection.contains("<")) {
            var binaryName = injection.substring(0, injection.indexOf('<'));
            var tokens = new ArrayDeque<>(SourceToken.lex(injection.substring(injection.indexOf('<'))));
            var parameters = SourceToken.readSignature(tokens, classFinder);
            return new ClassType(binaryName, binaryName.replace('.', '/'), parameters, null);
        }
        return new ClassType(injection, injection.replace('.', '/'), List.of(), null);
    }

    static TypeSignature parseFabricInjection(String injection) {
        var tokens = new ArrayDeque<>(BinaryToken.lex("L"+injection+";"));
        return BinaryToken.readType(tokens);
    }

    static TypeSignature parseSource(String source, ClassFinder classFinder) {
        var tokens = new ArrayDeque<>(SourceToken.lex(source));
        return SourceToken.readType(tokens, classFinder);
    }

    static TypeSignature parseBinary(String binary) {
        var tokens = new ArrayDeque<>(BinaryToken.lex(binary));
        return BinaryToken.readType(tokens);
    }

    private sealed interface BinaryToken {
        enum Simple implements BinaryToken {
            OPEN('<'),
            CLOSE('>'),
            EXTENDS('+'),
            SUPER('-'),
            WILDCARD('*'),
            FLOAT('F'),
            DOUBLE('D'),
            LONG('J'),
            INT('I'),
            SHORT('S'),
            CHAR('C'),
            BYTE('B'),
            BOOLEAN('Z'),
            VOID('V'),
            ARRAY('['),
            SEMICOLON(';');

            private final char representation;

            Simple(char representation) {
                this.representation = representation;
            }

            @Override
            public String toString() {
                return Character.toString(representation);
            }
        }

        record ClassName(String name) implements BinaryToken {
            @Override
            public String toString() {
                return "L"+name;
            }
        }

        record SuffixName(String name) implements BinaryToken {
            @Override
            public String toString() {
                return "."+name;
            }
        }

        record TypeVariable(String name) implements BinaryToken {
            @Override
            public String toString() {
                return "T"+name;
            }
        }

        static List<BinaryToken> lex(String signature) {
            int index = 0;
            List<BinaryToken> binaryTokens = new ArrayList<>();
            var chars = signature.toCharArray();
            while (index < chars.length) {
                var c = chars[index];
                switch (c) {
                    case '<' -> binaryTokens.add(BinaryToken.Simple.OPEN);
                    case '>' -> binaryTokens.add(BinaryToken.Simple.CLOSE);
                    case '+' -> binaryTokens.add(BinaryToken.Simple.EXTENDS);
                    case '-' -> binaryTokens.add(BinaryToken.Simple.SUPER);
                    case '*' -> binaryTokens.add(BinaryToken.Simple.WILDCARD);
                    case '[' -> binaryTokens.add(BinaryToken.Simple.ARRAY);
                    case 'F' -> binaryTokens.add(BinaryToken.Simple.FLOAT);
                    case 'D' -> binaryTokens.add(BinaryToken.Simple.DOUBLE);
                    case 'J' -> binaryTokens.add(BinaryToken.Simple.LONG);
                    case 'I' -> binaryTokens.add(BinaryToken.Simple.INT);
                    case 'S' -> binaryTokens.add(BinaryToken.Simple.SHORT);
                    case 'C' -> binaryTokens.add(BinaryToken.Simple.CHAR);
                    case 'B' -> binaryTokens.add(BinaryToken.Simple.BYTE);
                    case 'Z' -> binaryTokens.add(BinaryToken.Simple.BOOLEAN);
                    case 'V' -> binaryTokens.add(BinaryToken.Simple.VOID);
                    case ';' -> binaryTokens.add(BinaryToken.Simple.SEMICOLON);
                    case 'T' -> {
                        var start = index+1;
                        while (index < chars.length && chars[index] != ';') {
                            index++;
                        }
                        binaryTokens.add(new BinaryToken.TypeVariable(signature.substring(start, index)));
                        binaryTokens.add(BinaryToken.Simple.SEMICOLON);
                    }
                    case '.' -> {
                        var start = index+1;
                        while (index < chars.length && chars[index] != ';' && chars[index] != '<' && chars[index] != '.') {
                            index++;
                        }
                        binaryTokens.add(new BinaryToken.SuffixName(signature.substring(start, index)));
                        index--;
                    }
                    case 'L' -> {
                        var start = index+1;
                        while (index < chars.length && chars[index] != ';' && chars[index] != '<' && chars[index] != '.') {
                            index++;
                        }
                        binaryTokens.add(new BinaryToken.ClassName(signature.substring(start, index)));
                        index--;
                    }
                }
                index++;
            }
            return binaryTokens;
        }

        private static List<TypeSignature> readSignature(Deque<BinaryToken> tokens) {
            readToken(tokens, BinaryToken.Simple.OPEN);

            List<TypeSignature> parameters = new ArrayList<>();
            while (tokens.peek() != BinaryToken.Simple.CLOSE) {
                parameters.add(readType(tokens));
            }

            readToken(tokens, BinaryToken.Simple.CLOSE);

            return parameters;
        }

        private static TypeSignature readType(Deque<BinaryToken> tokens) {
            var token = tokens.poll();
            return switch (token) {
                case Simple.ARRAY -> new ArrayType(readType(tokens));
                case Simple.BOOLEAN -> Primitive.BOOLEAN;
                case Simple.BYTE -> Primitive.BYTE;
                case Simple.CHAR -> Primitive.CHAR;
                case Simple.DOUBLE -> Primitive.DOUBLE;
                case Simple.FLOAT -> Primitive.FLOAT;
                case Simple.INT -> Primitive.INT;
                case Simple.LONG -> Primitive.LONG;
                case Simple.SHORT -> Primitive.SHORT;
                case Simple.VOID -> Primitive.VOID;
                case Simple.WILDCARD -> WildcardType.INSTANCE;
                case Simple.EXTENDS -> new BoundedType(true, readType(tokens));
                case Simple.SUPER -> new BoundedType(false, readType(tokens));
                case ClassName className -> {
                    var binaryName = className.name;
                    var parameters = List.<TypeSignature>of();
                    if (tokens.peek() == BinaryToken.Simple.OPEN) {
                        parameters = readSignature(tokens);
                    }
                    ClassTypeSuffix suffix = null;
                    if (tokens.peek() instanceof SuffixName) {
                        suffix = readSuffix(tokens);
                    }
                    readToken(tokens, BinaryToken.Simple.SEMICOLON);
                    yield new ClassType(binaryName, binaryName.replace('/', '.'), parameters, suffix);
                }
                case TypeVariable typeVariable -> {
                    var name = typeVariable.name;
                    readToken(tokens, BinaryToken.Simple.SEMICOLON);
                    yield new ParameterType(name);
                }
                case null -> throw new IllegalArgumentException("Expected type, found EOF");
                default -> throw new IllegalArgumentException("Expected type, found token "+token);
            };
        }

        private static ClassTypeSuffix readSuffix(Deque<BinaryToken> tokens) {
            if (tokens.peek() instanceof SuffixName suffixName) {
                tokens.poll();

                var outName = suffixName.name;
                var parameters = List.<TypeSignature>of();
                if (tokens.peek() == BinaryToken.Simple.OPEN) {
                    parameters = readSignature(tokens);
                }
                ClassTypeSuffix suffix = null;
                if (tokens.peek() instanceof SuffixName) {
                    suffix = readSuffix(tokens);
                }
                return new ClassTypeSuffix(outName, parameters, suffix);
            }

            throw new IllegalArgumentException("Expected type suffix, found token "+tokens.peek());
        }

        private static void readToken(Deque<BinaryToken> tokens, BinaryToken expected) {
            var token = tokens.poll();
            if (token != expected) {
                throw new IllegalArgumentException("Expected "+expected+", found "+token);
            }
        }
    }

    private sealed interface SourceToken {
        enum Simple implements SourceToken {
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

        record Name(String name) implements SourceToken {
            @Override
            public String toString() {
                return name;
            }
        }

        private static boolean isInName(int c) {
            return Character.isJavaIdentifierPart(c) || Character.isWhitespace(c) || c == '.';
        }

        private static List<SourceToken> lex(String signature) {
            int index = 0;
            List<SourceToken> sourceTokens = new ArrayList<>();
            var chars = signature.codePoints().toArray();
            while (index < chars.length) {
                var c = chars[index];
                switch (c) {
                    case '<' -> sourceTokens.add(SourceToken.Simple.OPEN);
                    case '>' -> sourceTokens.add(SourceToken.Simple.CLOSE);
                    case '?' -> {
                        sourceTokens.add(SourceToken.Simple.WILDCARD);
                        index++;
                        while (index < chars.length && Character.isWhitespace(chars[index])) {
                            index++;
                        }
                        int extendsLength = "extends".length();
                        int superLength = "super".length();
                        if (index + extendsLength < chars.length && new String(chars, index, extendsLength).equals("extends")) {
                            sourceTokens.add(SourceToken.Simple.EXTENDS);
                            index += extendsLength-1;
                        } else if (index + superLength < chars.length && new String(chars, index, superLength).equals("super")) {
                            sourceTokens.add(SourceToken.Simple.SUPER);
                            index += superLength-1;
                        } else {
                            index--;
                        }
                    }
                    case ',' -> sourceTokens.add(SourceToken.Simple.COMMA);
                    case '[' -> {
                        index++;
                        while (index < chars.length && Character.isWhitespace(chars[index])) {
                            index++;
                        }
                        if (chars[index] != ']') {
                            throw new IllegalArgumentException("Expected `]`, found "+Character.toString(chars[index]));
                        }
                        sourceTokens.add(SourceToken.Simple.ARRAY);
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
                            sourceTokens.add(switch (cleanedName) {
                                case "extends" -> SourceToken.Simple.EXTENDS;
                                case "super" -> SourceToken.Simple.SUPER;
                                default -> new SourceToken.Name(cleanedName);
                            });
                            index--;
                        }
                    }
                }
                index++;
            }
            return sourceTokens;
        }

        private static List<TypeSignature> readSignature(Deque<SourceToken> tokens, ClassFinder classFinder) {
            readToken(tokens, SourceToken.Simple.OPEN);

            List<TypeSignature> parameters = new ArrayList<>();
            while (tokens.peek() != SourceToken.Simple.CLOSE) {
                parameters.add(readType(tokens, classFinder));
                if (tokens.peek() == SourceToken.Simple.COMMA) {
                    tokens.poll();
                } else if (tokens.peek() != SourceToken.Simple.CLOSE) {
                    throw new IllegalArgumentException("Expected `,` or `>`, found "+tokens.peek());
                }
            }

            readToken(tokens, SourceToken.Simple.CLOSE);

            return parameters;
        }

        private static TypeSignature readNonArrayType(Deque<SourceToken> tokens, ClassFinder classFinder) {
            if (tokens.peek() == SourceToken.Simple.WILDCARD) {
                tokens.poll();
                if (tokens.peek() == SourceToken.Simple.EXTENDS) {
                    tokens.poll();
                    return new BoundedType(true, readType(tokens, classFinder));
                } else if (tokens.peek() == SourceToken.Simple.SUPER) {
                    tokens.poll();
                    return new BoundedType(false, readType(tokens, classFinder));
                } else {
                    return WildcardType.INSTANCE;
                }
            } else if (tokens.peek() instanceof SourceToken.Name name) {
                tokens.poll();
                return switch (name.name) {
                    case "int" -> Primitive.INT;
                    case "boolean" -> Primitive.BOOLEAN;
                    case "byte" -> Primitive.BYTE;
                    case "char" -> Primitive.CHAR;
                    case "short" -> Primitive.SHORT;
                    case "void" -> Primitive.VOID;
                    case "float" -> Primitive.FLOAT;
                    case "double" -> Primitive.DOUBLE;
                    case "long" -> Primitive.LONG;
                    default -> {
                        var sourceName = name.name;
                        boolean isParameter = !sourceName.contains(".") && tokens.peek() != SourceToken.Simple.OPEN;
                        if (isParameter) {
                            yield new ParameterType(sourceName);
                        }
                        var parts = sourceName.split("\\.");
                        String binaryName = sourceName.replace('.', '/');
                        for (int i = parts.length; i > 0; i--) {
                            var combined =
                                String.join("/", Arrays.copyOfRange(parts, 0, i))
                                    + (i == parts.length ? "" : String.join("$", Arrays.copyOfRange(parts, i, parts.length)));
                            if (classFinder.test(combined)) {
                                binaryName = combined;
                            }
                        }
                        var parameters = List.<TypeSignature>of();
                        if (tokens.peek() == SourceToken.Simple.OPEN) {
                            parameters = readSignature(tokens, classFinder);
                        }
                        ClassTypeSuffix suffix = null;
                        if (tokens.peek() instanceof SourceToken.Name suffixName && suffixName.name.startsWith(".")) {
                            suffix = readSuffix(tokens, classFinder);
                        }
                        yield new ClassType(binaryName, sourceName, parameters, suffix);
                    }
                };
            }

            throw new IllegalArgumentException("Expected type, found token "+tokens.peek());
        }

        private static ClassTypeSuffix readSuffix(Deque<SourceToken> tokens, ClassFinder classFinder) {
            if (tokens.peek() instanceof Name name && name.name.startsWith(".")) {
                tokens.poll();

                var outName = name.name.substring(1);
                var parameters = List.<TypeSignature>of();
                if (tokens.peek() == SourceToken.Simple.OPEN) {
                    parameters = readSignature(tokens, classFinder);
                }
                ClassTypeSuffix suffix = null;
                if (tokens.peek() instanceof SourceToken.Name suffixName && suffixName.name.startsWith(".")) {
                    suffix = readSuffix(tokens, classFinder);
                }
                return new ClassTypeSuffix(outName, parameters, suffix);
            }

            throw new IllegalArgumentException("Expected type suffix, found token "+tokens.peek());
        }

        private static TypeSignature readType(Deque<SourceToken> tokens, ClassFinder classFinder) {
            var type = readNonArrayType(tokens, classFinder);

            while (tokens.peek() == SourceToken.Simple.ARRAY) {
                tokens.poll();
                type = new ArrayType(type);
            }

            return type;
        }

        private static void readToken(Deque<SourceToken> tokens, SourceToken expected) {
            var token = tokens.poll();
            if (token != expected) {
                throw new IllegalArgumentException("Expected "+expected+", found "+token);
            }
        }
    }

    private static String forId(int id) {
        StringBuilder sb = new StringBuilder();
        while (id >= 0) {
            sb.append((char) ('A' + id % 26));
            id /= 26;
            id -= 1;
        }
        return sb.reverse().toString();
    }

    enum WildcardType implements TypeSignature {
        INSTANCE;

        @Override
        public String binary() {
            return "*";
        }

        @Override
        public String source() {
            return "?";
        }

        @Override
        public String toString() {
            return binary();
        }
    }

    record ArrayType(TypeSignature component) implements TypeSignature {
        @Override
        public String binary() {
            return "["+component.binary();
        }

        @Override
        public String source() {
            return component.source()+"[]";
        }

        @Override
        public String toString() {
            return binary();
        }
    }

    record BoundedType(boolean isExtends, TypeSignature bound) implements TypeSignature {
        @Override
        public String binary() {
            return (isExtends ? "+":"-")+bound.binary();
        }

        @Override
        public String source() {
            return "? "+(isExtends ? "extends" : "super")+" "+bound.source();
        }

        @Override
        public String toString() {
            return binary();
        }
    }

    record ParameterType(String name) implements TypeSignature {
        @Override
        public String binary() {
            return "T"+name+";";
        }

        @Override
        public String source() {
            return name;
        }

        @Override
        public String toString() {
            return binary();
        }
    }

    record ClassTypeSuffix(String name, List<TypeSignature> parameters, @Nullable ClassTypeSuffix suffix) implements TypeSignature {
        @Override
        public String toString() {
            return binary();
        }

        @Override
        public String binary() {
            var builder = new StringBuilder(".").append(name);
            if (!parameters.isEmpty()) {
                builder.append("<");
                for (var parameter : parameters) {
                    builder.append(parameter.binary());
                }
                builder.append(">");
            }
            if (suffix != null) {
                builder.append(suffix.binary());
            }
            return builder.toString();
        }

        @Override
        public String source() {
            var builder = new StringBuilder(".").append(name);
            if (!parameters.isEmpty()) {
                builder.append("<");
                boolean first = true;
                for (var parameter : parameters) {
                    if (!first) {
                        builder.append(", ");
                    } else {
                        first = false;
                    }
                    builder.append(parameter.source());
                }
                builder.append(">");
            }
            if (suffix != null) {
                builder.append(suffix.source());
            }
            return builder.toString();
        }
    }

    record ClassType(String binaryName, String sourceName, List<TypeSignature> parameters, @Nullable ClassTypeSuffix suffix) implements TypeSignature {
        @Override
        public String binary() {
            var builder = new StringBuilder("L").append(binaryName);
            if (!parameters.isEmpty()) {
                builder.append("<");
                for (var parameter : parameters) {
                    builder.append(parameter.binary());
                }
                builder.append(">");
            }
            if (suffix != null) {
                builder.append(suffix.binary());
            }
            return builder.append(";").toString();
        }

        @Override
        public String source() {
            var builder = new StringBuilder(sourceName);
            if (!parameters.isEmpty()) {
                builder.append("<");
                boolean first = true;
                for (var parameter : parameters) {
                    if (!first) {
                        builder.append(", ");
                    } else {
                        first = false;
                    }
                    builder.append(parameter.source());
                }
                builder.append(">");
            }
            if (suffix != null) {
                builder.append(suffix.source());
            }
            return builder.toString();
        }

        @Override
        public String neo() {
            var builder = new StringBuilder(binaryName);
            if (!parameters.isEmpty()) {
                builder.append("<");
                boolean first = true;
                for (var parameter : parameters) {
                    if (!first) {
                        builder.append(", ");
                    } else {
                        first = false;
                    }
                    builder.append(parameter.source());
                }
                builder.append(">");
            }
            if (suffix != null) {
                builder.append(suffix.source());
            }
            return builder.toString();
        }

        @Override
        public String fabric() {
            var builder = new StringBuilder(binaryName);
            if (!parameters.isEmpty()) {
                builder.append("<");
                for (var parameter : parameters) {
                    builder.append(parameter.binary());
                }
                builder.append(">");
            }
            if (suffix != null) {
                builder.append(suffix.binary());
            }
            return builder.toString();
        }

        @Override
        public byte[] binaryStub() {
            if (suffix != null) {
                throw new UnsupportedOperationException("Cannot create binary stub for class type with suffix");
            }
            var classWriter = new ClassWriter(0);
            String signature = null;
            if (!parameters.isEmpty()) {
                StringBuilder signatureBuilder = new StringBuilder("<");
                for (int i = 0; i < parameters.size(); i++) {
                    signatureBuilder.append(forId(i));
                    signatureBuilder.append(":Ljava/lang/Object;");
                }
                signatureBuilder.append(">Ljava/lang/Object;");
                signature = signatureBuilder.toString();
            }
            classWriter.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                binaryName,
                signature,
                "java/lang/Object",
                new String[0]
            );
            classWriter.visitEnd();
            return classWriter.toByteArray();
        }

        @Override
        public String sourceStub() {
            if (suffix != null) {
                throw new UnsupportedOperationException("Cannot create source stub for class type with suffix");
            }
            var signature = "";
            if (!parameters.isEmpty()) {
                StringBuilder signatureBuilder = new StringBuilder("<");
                for (int i = 0; i < parameters.size(); i++) {
                    if (i != 0) {
                        signatureBuilder.append(", ");
                    }
                    signatureBuilder.append(forId(i));
                }
                signatureBuilder.append(">");
                signature = signatureBuilder.toString();
            }
            String packageName = sourceName.substring(0, sourceName.lastIndexOf('.'));
            String className = sourceName.substring(sourceName.lastIndexOf('.') + 1);
            return """
                package %s;

                public interface %s%s {}
                """.formatted(packageName, className, signature);
        }

        @Override
        public String toString() {
            return binary();
        }
    }

    enum Primitive implements TypeSignature {
        INT("I", "int"),
        BOOLEAN("Z", "boolean"),
        BYTE("B", "byte"),
        CHAR("C", "char"),
        SHORT("S", "short"),
        VOID("V", "void"),
        FLOAT("F", "float"),
        DOUBLE("D", "double"),
        LONG("J", "long");

        private final String binary;
        private final String source;

        Primitive(String binary, String source) {
            this.binary = binary;
            this.source = source;
        }

        @Override
        public String binary() {
            return binary;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public String toString() {
            return binary();
        }
    }
}
