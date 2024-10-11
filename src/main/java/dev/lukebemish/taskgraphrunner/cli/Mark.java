package dev.lukebemish.taskgraphrunner.cli;

import com.google.gson.reflect.TypeToken;
import dev.lukebemish.taskgraphrunner.runtime.util.FileUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.JsonUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            var now = FileTime.from(Instant.now());
            for (var taskRecordJson : taskRecordJsons) {
                try (var reader = Files.newBufferedReader(taskRecordJson)) {
                    Map<String, SingleTask> tasks = JsonUtils.GSON.fromJson(reader, TypeToken.getParameterized(Map.class, String.class, SingleTask.class).getType());
                    for (var entry : tasks.entrySet()) {
                        var task = entry.getValue();
                        var state = Paths.get(task.state);
                        var name = state.getFileName().toString();
                        name = name.substring(0, name.length() - ".json".length());
                        try (var ignored = lockManager.lock("task."+state.getParent().getFileName().toString()+"."+name)) {
                            for (var output : task.outputs) {
                                var outputPath = Paths.get(output);
                                if (Files.exists(outputPath)) {
                                    FileUtils.setLastAccessedTime(outputPath, now);
                                }
                            }
                            if (Files.exists(state)) {
                                FileUtils.setLastAccessedTime(state, now);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
