package dev.lukebemish.taskgraphrunner.instrumentation;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class ASMUtils {
    private ASMUtils() {}

    static ConstantDynamic booleanConstant(boolean bool) {
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
}
