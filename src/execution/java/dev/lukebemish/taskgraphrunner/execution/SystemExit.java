package dev.lukebemish.taskgraphrunner.execution;

public class SystemExit extends Throwable {
    private final int status;

    public SystemExit(int status) {
        this.status = status;
    }

    public SystemExit(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
