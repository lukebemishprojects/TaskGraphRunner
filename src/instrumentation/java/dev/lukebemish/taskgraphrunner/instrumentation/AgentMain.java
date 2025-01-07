package dev.lukebemish.taskgraphrunner.instrumentation;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

public final class AgentMain {
    private AgentMain() {}

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ZipOutputStreamRetransformer(), true);
        instrumentation.addTransformer(new ZipFileSystemRetransformer(), true);
        try {
            Class<?> zipOutputStream = Class.forName("java.util.zip.ZipOutputStream");
            instrumentation.retransformClasses(zipOutputStream);
        } catch (Throwable t) {
            System.err.printf("Failed to retransform ZipOutputStream: %s%n", t);
        }
        try {
            List<Class<?>> classes = new ArrayList<>();
            Class<?> zipFileSystem = Class.forName("jdk.nio.zipfs.ZipFileSystem");

            var nestFinder = new Object() {
                void findNested(Class<?> clazz) {
                    classes.add(clazz);
                    for (Class<?> nested : clazz.getDeclaredClasses()) {
                        findNested(nested);
                    }
                }
            };

            nestFinder.findNested(zipFileSystem);
            instrumentation.retransformClasses(classes.toArray(Class<?>[]::new));
        } catch (Throwable t) {
            System.err.printf("Failed to retransform ZipOutputStream: %s%n", t);
        }
    }
}
