package dev.lukebemish.taskgraphrunner.cli;

import picocli.CommandLine;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "daemon", mixinStandardHelpOptions = true, description = "Start a daemon for running TaskGraphRunner operations that can execute multiple actions.")
public class Daemon implements AutoCloseable, Runnable {
    public static void main(String[] args) {
        try (var daemon = new Daemon(new Main(args))) {
            daemon.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void run() {
        RuntimeException finalException = null;
        try {
            runExceptional();
        } catch (IOException e) {
            finalException = new UncheckedIOException(e);
        } finally {
            try {
                close();
            } catch (IOException e) {
                if (finalException != null) {
                    finalException.addSuppressed(e);
                } else {
                    finalException = new UncheckedIOException(e);
                }
            }
        }
        if (finalException != null) {
            throw finalException;
        }
    }

    private final ServerSocket socket;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("TaskGraphRunnerDaemon-",1).factory());
    private final Main main;
    Daemon(Main main) {
        try {
            this.socket = new ServerSocket(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.main = main;
    }

    private void runExceptional() throws IOException {
        // This tells the parent process what port we're listening on
        System.out.println(socket.getLocalPort());
        var socket = this.socket.accept();
        var input = new DataInputStream(socket.getInputStream());
        var os = new DataOutputStream(socket.getOutputStream());
        var output = new Output(os);
        while (true) {
            int id = input.readInt();
            if (id == -1) {
                break;
            }
            String[] args = new String[main.args.length - 1 + input.readInt()];
            System.arraycopy(main.args, 0, args, 0, main.args.length - 1);
            for (int i = 0; i < args.length; i++) {
                args[i + main.args.length-1] = new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8);
            }
            executor.submit(() -> {
                Main.execute(() -> {
                    try {
                        var result = new Main(args).execute(args);
                        if (result != 0) {
                            throw new IOException("Non-zero exit code: "+result);
                        }
                        output.writeSuccess(id);
                    } catch (Throwable e) {
                        try {
                            output.writeFailure(id);
                        } catch (Throwable ex) {
                            var both = new RuntimeException(ex);
                            both.addSuppressed(e);
                            throw both;
                        }
                        throw new RuntimeException(e);
                    }
                });
            });
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        executor.shutdownNow();
        try {
            executor.awaitTermination(4000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Main.logException(e);
            throw new RuntimeException(e);
        }
    }

    private record Output(DataOutputStream output) {
        synchronized void writeFailure(int id) throws IOException {
            output.writeInt(id);
            output.writeBoolean(false);
            output.flush();
        }

        synchronized void writeSuccess(int id) throws IOException {
            output.writeInt(id);
            output.writeBoolean(true);
            output.flush();
        }
    }
}
