package dev.lukebemish.taskgraphrunner.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

public class ZipFileSystemRetransformer implements ClassFileTransformer {
    ZipFileSystemRetransformer() {}

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        // Let's just target everything nested in ZipFileSystem
        if (className.equals("jdk/nio/zipfs/ZipFileSystem") || className.startsWith("jdk/nio/zipfs/ZipFileSystem$")) {
            var classReader = new ClassReader(classfileBuffer);
            var writer = new ClassWriter(0);
            var visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    // We're less selective than with ZOS here -- since the entries are not created in the file system, we can get away with just replacing the call to System.currentTimeMillis
                    var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (name.equals("currentTimeMillis") && opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System") && descriptor.equals("()J")) {
                                super.visitLdcInsn(0L);
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
                        }
                    };
                }
            };
            classReader.accept(visitor, 0);
            return writer.toByteArray();
        }
        return ClassFileTransformer.super.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }
}
