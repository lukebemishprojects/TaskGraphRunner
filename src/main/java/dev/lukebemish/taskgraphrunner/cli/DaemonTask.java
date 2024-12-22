package dev.lukebemish.taskgraphrunner.cli;

import dev.lukebemish.forkedtaskexecutor.runner.Task;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@CommandLine.Command(name = "daemon", mixinStandardHelpOptions = true, description = "Start a daemon for running TaskGraphRunner operations that can execute multiple actions.")
public class DaemonTask implements Task, Runnable {
    private final Main main;

    public DaemonTask(String[] args) {
        this.main = new Main(args);
        if (new CommandLine(this.main)
            .addSubcommand("daemon", this)
            .execute(args) != 0) {
            throw new RuntimeException("Issues starting daemon");
        }
    }

    @Override
    public byte[] run(byte[] bytes) throws Exception {
        byte[] out = new byte[1];
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String[] args = new String[main.args.length - 1 + input.readInt()];
            System.arraycopy(main.args, 0, args, 0, main.args.length - 1);
            for (int i = main.args.length - 1; i < args.length; i++) {
                args[i] = new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8);
            }
            try {
                var result = new Main(args).execute(args);
                if (result != 0) {
                    throw new IOException("Non-zero exit code: "+result);
                }
            } catch (Throwable e) {
                out[0] = 1;
                throw new RuntimeException(e);
            }
        }
        return out;
    }

    @Override
    public void run() {
        // no-op -- we just needed to initialize everything
    }
}
