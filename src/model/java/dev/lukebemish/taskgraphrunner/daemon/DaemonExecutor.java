package dev.lukebemish.taskgraphrunner.daemon;

import dev.lukebemish.forkedtaskexecutor.ForkedTaskExecutor;
import dev.lukebemish.forkedtaskexecutor.ForkedTaskExecutorSpec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

public class DaemonExecutor implements AutoCloseable {
    private final ForkedTaskExecutor executor;

    public DaemonExecutor(Consumer<ForkedTaskExecutorSpec.Builder> runnerProcessConfigurator) {
        var builder = ForkedTaskExecutorSpec.builder();
        runnerProcessConfigurator.accept(builder);
        builder.addProgramOption("daemon")
            .taskClass("dev.lukebemish.taskgraphrunner.cli.DaemonTask");
        this.executor = new ForkedTaskExecutor(builder.build());
    }

    @Override
    public synchronized void close() {
        executor.close();
    }

    private static void writeString(DataOutputStream os, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        os.writeInt(bytes.length);
        os.write(bytes);
    }

    public void execute(String[] args) {
        try {
            var output = new ByteArrayOutputStream();
            try (var os = new DataOutputStream(output)) {
                os.writeInt(args.length);
                for (String arg : args) {
                    writeString(os, arg);
                }
            }
            byte[] result = executor.submit(output.toByteArray());
            if (result.length != 1) {
                throw new IOException("Unexpected result: " + Arrays.toString(result));
            }
            if (result[0] != 0) {
                throw new IOException("Process failed");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
