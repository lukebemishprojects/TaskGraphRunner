package dev.lukebemish.taskgraphrunner.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.attribute.FileTime;
import java.security.ProtectionDomain;
import java.util.zip.ZipEntry;

public final class ZipOutputStreamRetransformer implements ClassFileTransformer {
    ZipOutputStreamRetransformer() {}

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className.equals("java/util/zip/ZipOutputStream")) {
            var classReader = new ClassReader(classfileBuffer);
            var writer = new ClassWriter(0);
            var visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (name.equals("putNextEntry") && descriptor.equals("(Ljava/util/zip/ZipEntry;)V")) {
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                delegate.visitCode();

                                var systemClassloader = invoke(
                                    ClassLoader.class.descriptorString(),
                                    new Handle(
                                        Opcodes.H_INVOKESTATIC,
                                        Type.getInternalName(ClassLoader.class),
                                        "getSystemClassLoader",
                                        MethodType.methodType(ClassLoader.class).descriptorString(),
                                        false
                                    )
                                );

                                var transformerClass = invoke(
                                    Class.class.descriptorString(),
                                    new Handle(
                                        Opcodes.H_INVOKESTATIC,
                                        Type.getInternalName(Class.class),
                                        "forName",
                                        MethodType.methodType(Class.class, String.class, boolean.class, ClassLoader.class).descriptorString(),
                                        false
                                    ),
                                    ZipOutputStreamRetransformer.class.getName(),
                                    booleanConstant(false),
                                    systemClassloader
                                );

                                var lookup = invoke(
                                    MethodHandles.Lookup.class.descriptorString(),
                                    new Handle(
                                        Opcodes.H_INVOKESTATIC,
                                        Type.getInternalName(MethodHandles.class),
                                        "publicLookup",
                                        MethodType.methodType(MethodHandles.Lookup.class).descriptorString(),
                                        false
                                    )
                                );

                                var standardizeHandle = invoke(
                                    MethodHandle.class.descriptorString(),
                                    new Handle(
                                        Opcodes.H_INVOKEVIRTUAL,
                                        Type.getInternalName(MethodHandles.Lookup.class),
                                        "findStatic",
                                        MethodType.methodType(MethodHandle.class, Class.class, String.class, MethodType.class).descriptorString(),
                                        false
                                    ),
                                    lookup,
                                    transformerClass,
                                    "standardize",
                                    Type.getMethodType(Type.getType(ZipEntry.class), Type.getType(ZipEntry.class))
                                );

                                delegate.visitLdcInsn(standardizeHandle);
                                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                                delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "(Ljava/util/zip/ZipEntry;)Ljava/util/zip/ZipEntry;", false);
                                delegate.visitVarInsn(Opcodes.ASTORE, 1);
                            }
                        };
                    }
                    return delegate;
                }
            };
            classReader.accept(visitor, 0);
            return writer.toByteArray();
        }
        return ClassFileTransformer.super.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    private static ConstantDynamic invoke(String descriptor, Object handle, Object... args) {
        Object[] fullArgs = new Object[args.length+1];
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        fullArgs[0] = handle;
        return new ConstantDynamic(
            "invoke",
            descriptor,
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(ConstantBootstraps.class),
                "invoke",
                MethodType.methodType(Object.class, MethodHandles.Lookup.class, String.class, Class.class, MethodHandle.class, Object[].class).descriptorString(),
                false
            ),
            fullArgs
        );
    }

    private static ConstantDynamic booleanConstant(boolean bool) {
        return new ConstantDynamic(
            // booleans are funky in ConstantDynamics. Here's an alternative...
            bool ? "TRUE" : "FALSE",
            Boolean.class.descriptorString(),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(ConstantBootstraps.class),
                "getStaticFinal",
                MethodType.methodType(Object.class, MethodHandles.Lookup.class, String.class, Class.class, Class.class).descriptorString(),
                false
            ),
            Type.getType(Boolean.class)
        );
    }

    public static ZipEntry standardize(ZipEntry zipEntry) {
        zipEntry.setCreationTime(FileTime.fromMillis(0));
        zipEntry.setLastAccessTime(FileTime.fromMillis(0));
        zipEntry.setLastModifiedTime(FileTime.fromMillis(0));
        return zipEntry;
    }
}
