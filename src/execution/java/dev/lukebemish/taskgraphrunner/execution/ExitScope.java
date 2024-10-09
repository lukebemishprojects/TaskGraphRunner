package dev.lukebemish.taskgraphrunner.execution;

import java.util.concurrent.atomic.AtomicInteger;

public class ExitScope {
    static final InheritableThreadLocal<ExitScope> SCOPE = new InheritableThreadLocal<>();

    private final AtomicInteger exitStatus = new AtomicInteger(0);

    public static void exit(int status) throws SystemExit {
        ExitScope exitScope = SCOPE.get();
        if (exitScope == null) {
            throw new SystemExit(status, "No ExitScopedClassLoader found in the stack trace; this may not be properly caught.");
        }
        exitScope.exitStatus.set(status);
        throw new SystemExit(status);
    }

    int exitStatus() {
        return exitStatus.get();
    }
}
