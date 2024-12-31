package dev.lukebemish.taskgraphrunner.instrumentation;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.zip.ZipOutputStream;

public final class AgentMain {
    private AgentMain() {}

    public static void premain(String args, Instrumentation instrumentation) throws UnmodifiableClassException {
        instrumentation.addTransformer(new ZipOutputStreamRetransformer(), true);
        instrumentation.retransformClasses(ZipOutputStream.class);
    }
}
