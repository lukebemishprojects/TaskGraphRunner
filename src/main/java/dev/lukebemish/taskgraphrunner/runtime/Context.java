package dev.lukebemish.taskgraphrunner.runtime;

import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface Context {
    Path taskOutputPath(String taskName, String outputName);

    Path taskStatePath(String taskName);

    Path taskDirectory(String taskName);

    Path taskWorkingDirectory(String taskName);

    Task getTask(String name);

    Path findArtifact(String notation);

    ArtifactManifest artifactManifest();

    LockManager lockManager();

    private void collectDependencies(Set<String> tasks, Task task, Set<String> visited) {
        if (visited.contains(task.name())) {
            throw new IllegalArgumentException("Circular dependency detected on task " + task.name());
        }
        var newVisited = new HashSet<>(visited);
        newVisited.add(task.name());
        tasks.add(task.name());
        for (var input : task.inputs()) {
            for (var dependency : input.dependencies()) {
                collectDependencies(tasks, getTask(dependency), newVisited);
            }
        }
    }

    default LockManager.Locks locks(Set<String> tasks) {
        Set<String> allTasks = new HashSet<>();
        for (String task : tasks) {
            collectDependencies(allTasks, getTask(task), Set.of());
        }
        return locks0(tasks);
    }

    private LockManager.Locks locks0(Set<String> tasks) {
        List<String> keys = new ArrayList<>();
        for (String task : tasks) {
            keys.add(taskDirectory(task).getFileName().toString());
        }
        return lockManager().locks(keys);
    }

    boolean useCached();
}
