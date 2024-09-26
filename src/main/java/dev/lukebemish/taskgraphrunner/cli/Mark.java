package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@CommandLine.Command(name = "mark", mixinStandardHelpOptions = true, description = "Mark results of tasks ran in a previous execution as having been consumed now for the purposes of cache cleanup")
public class Mark implements Runnable {
    @CommandLine.Parameters(
        index = "*",
        description = "Specifies a file to record what tasks were executed and what their outputs were"
    )
    List<Path> taskRecordJsons;

    public Mark(Main main) {
        this.main = main;
    }

    private record SingleTask(String type, String state, List<String> outputs) {}

    private final Main main;

    @Override
    public void run() {
        try {
            var lockManager = new LockManager(main.cacheDir.resolve("locks"));
            for (var taskRecordJson : taskRecordJsons) {
                var now = FileTime.from(Instant.now());
                try (var reader = Files.newBufferedReader(taskRecordJson)) {
                    Map<String, SingleTask> tasks = JsonUtils.GSON.fromJson(reader, TypeToken.getParameterized(Map.class, String.class, SingleTask.class).getType());
                    for (var entry : tasks.entrySet()) {
                        var task = entry.getValue();
                        var state = Paths.get(task.state);
                        try (var ignored = lockManager.lock(state.getParent().getFileName().toString())) {
                            for (var output : task.outputs) {
                                var outputPath = Paths.get(output);
                                if (Files.exists(outputPath)) {
                                    setLastAccessedTime(outputPath, now);
                                }
                            }
                            if (Files.exists(state)) {
                                JsonObject stateObject;
                                try (var stateReader = Files.newBufferedReader(state)) {
                                    stateObject = JsonUtils.GSON.fromJson(stateReader, JsonObject.class);
                                }
                                stateObject.addProperty("lastAccessed", now.toMillis());
                                try (var stateWriter = Files.newBufferedWriter(state)) {
                                    JsonUtils.GSON.toJson(stateObject, stateWriter);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setLastAccessedTime(Path path, FileTime now) throws IOException {
        Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(null, now, null);
    }
}
