package dev.lukebemish.taskgraphrunner.execution;

import java.io.InputStream;
import java.io.PrintStream;

public class SystemStreams {
    static final InheritableThreadLocal<PrintStream> OUT = new InheritableThreadLocal<>();
    static final InheritableThreadLocal<PrintStream> ERR = new InheritableThreadLocal<>();
    static final InputStream IN = InputStream.nullInputStream();

    public static InputStream in() {
        return IN;
    }

    public static PrintStream out() {
        var out = OUT.get();
        if (out == null) {
            throw new IllegalStateException("No PrintStream available for what would normally be System.out");
        }
        return out;
    }

    public static PrintStream err() {
        var err = ERR.get();
        if (err == null) {
            throw new IllegalStateException("No PrintStream available for what would normally be System.err");
        }
        return err;
    }
}
