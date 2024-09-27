package dev.lukebemish.taskgraphrunner.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.lukebemish.taskgraphrunner.model.TaskModel;
import dev.lukebemish.taskgraphrunner.model.WorkItem;
import dev.lukebemish.taskgraphrunner.runtime.tasks.CompileTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadAssetsTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadDistributionTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadJsonTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadManifestTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.DownloadMappingsTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.InjectSourcesTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.InterfaceInjectionTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.JstTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.ListClasspathTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.PatchSourcesTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.RetrieveDataTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.SplitClassesResourcesTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.ToolTask;
import dev.lukebemish.taskgraphrunner.runtime.tasks.TransformMappingsTask;
import dev.lukebemish.taskgraphrunner.runtime.util.HashUtils;
import dev.lukebemish.taskgraphrunner.runtime.util.LockManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public abstract class Task implements RecordedInput {
    private static final Logger LOGGER = LoggerFactory.getLogger(Task.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String name;
    private final String type;
    private final @Nullable String parallelism;

    public Task(TaskModel model) {
        this.name = model.name();
        this.type = model.type();
        this.parallelism = model.parallelism;
    }

    public String name() {
        return name;
    }

    public abstract List<TaskInput> inputs();

    public abstract Map<String, String> outputTypes();

    private volatile byte[] referenceHash;
    private volatile byte[] contentsHash;

    private final AtomicBoolean executed = new AtomicBoolean(false);
    private final AtomicBoolean submitted = new AtomicBoolean(false);

    private LockManager.LockLike lock;
    private final AtomicInteger lockedDependents = new AtomicInteger(0);
    private final AtomicInteger remainingDependencies = new AtomicInteger(0);

    protected int cacheVersion() {
        return 1;
    }

    private final Object unlockSynchronization = new Object();

    private String lockFileName(Context context) {
        return context.taskDirectory(this).getFileName().toString();
    }

    private void unlock0() {
        synchronized (unlockSynchronization) {
            var remaining = lockedDependents.get();
            LOGGER.debug("{} has {} dependents remaining", name, remaining);
            if (remaining <= 0 && lock != null) {
                lock.close();
                lock = null;
            }
        }
    }

    private void unlockDependencies(Context context) {
        synchronized (unlockSynchronization) {
            for (var input : inputs()) {
                for (var dependency : input.dependencies()) {
                    var task = context.getTask(dependency);
                    task.lockedDependents.decrementAndGet();
                    task.unlock0();
                }
            }
        }
    }

    private void lock0(Context context) {
        if (this.lock == null) {
            LOGGER.debug("Acquiring lock for task {}", name());
            this.lock = context.lockManager().managerScopedLock(lockFileName(context));
        }
    }

    public void clean() {
        synchronized (unlockSynchronization) {
            if (lock != null) {
                lock.close();
                lock = null;
            }
        }
    }

    private SafeClosable unlockOnCompletion(Context context) {
        return () -> unlockDependencies(context);
    }

    private record GraphNode(Task task, List<GraphNode> dependents, Map<String, GraphNode> dependentMap, Map<String, Path> outputs) {}

    private record Action(String task, Map<String, Path> outputs) {}

    private static Collection<GraphNode> assemble(Context context, Map<String, Map<String, Path>> actions) {
        Map<String, GraphNode> nodes = new HashMap<>();
        Queue<Action> queue = new ArrayDeque<>();
        for (var entry : actions.entrySet()) {
            queue.add(new Action(entry.getKey(), entry.getValue()));
        }
        while (!queue.isEmpty()) {
            var top = queue.poll();
            var taskName = top.task;
            var outputs = top.outputs;
            var task = context.getTask(taskName);
            var newNode = nodes.compute(taskName, (name, node) -> {
                if (node == null) {
                    return new GraphNode(task, new ArrayList<>(), new HashMap<>(), outputs);
                } else {
                    node.outputs.putAll(outputs);
                }
                return node;
            });
            int deps = 0;
            for (var input : task.inputs()) {
                for (var dependency : input.dependencies()) {
                    deps++;
                    nodes.compute(dependency, (name, node) -> {
                        if (node == null) {
                            var list = new ArrayList<GraphNode>();
                            list.add(newNode);
                            var map = new HashMap<String, GraphNode>();
                            map.put(taskName, newNode);
                            return new GraphNode(context.getTask(dependency), list, map, new HashMap<>());
                        } else {
                            if (!node.dependentMap.containsKey(taskName)) {
                                node.dependents.add(newNode);
                                node.dependentMap.put(taskName, newNode);
                            }
                        }
                        return node;
                    });
                    queue.add(new Action(dependency, new HashMap<>()));
                }
            }
            task.remainingDependencies.set(deps);
        }
        return nodes.values();
    }

    private static void executeNode(Context context, Consumer<Future<?>> futureConsumer, GraphNode node) {
        futureConsumer.accept(context.submit(() -> {
            LOGGER.debug("Executing task {} which is a dependency of {}", node.task.name(), node.dependentMap.keySet());
            node.task.lockedDependents.set(1+node.dependents.size());
            node.task.lock0(context);
            try {
                node.task.execute(context);
                for (var entry : node.outputs.entrySet()) {
                    var outputPath = context.taskOutputPath(node.task, entry.getKey());
                    try {
                        Files.copy(outputPath, entry.getValue(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            } finally {
                node.task.lockedDependents.getAndDecrement();
                node.task.unlock0();
            }
            for (var dependent : node.dependents) {
                var remaining = dependent.task.remainingDependencies.decrementAndGet();
                if (remaining <= 0) {
                    if (!dependent.task.submitted.getAndSet(true)) {
                        executeNode(context, futureConsumer, dependent);
                    }
                }
            }
        }));
    }

    static void executeTasks(Context context, Map<String, Map<String, Path>> actions) {
        var originalNodes = assemble(context, actions);
        var futures = new ConcurrentLinkedQueue<Future<?>>();
        for (var node : originalNodes) {
            if (node.task.remainingDependencies.get() <= 0) {
                executeNode(context, futures::add, node);
            }
        }
        List<Throwable> suppressed = new ArrayList<>();
        while (!futures.isEmpty()) {
            var future = futures.poll();
            try {
                future.get();
            } catch (Throwable t) {
                suppressed.add(t);
            }
        }
        if (!suppressed.isEmpty()) {
            var e = new RuntimeException("Failed to execute task", suppressed.getFirst());
            if (suppressed.size() > 1) {
                for (var t : suppressed.subList(1, suppressed.size())) {
                    e.addSuppressed(t);
                }
            }
            throw e;
        }
        for (var node : originalNodes) {
            if (!node.task.executed.get()) {
                throw new IllegalStateException("Task `" + node.task.name() + "` was not executed, likely due to a circular dependency");
            }
        }
    }

    private interface SafeClosable extends AutoCloseable {
        @Override
        void close();
    }

    boolean isExecuted() {
        return executed.get();
    }

    private void execute(Context context) {
        LOGGER.debug("Requested task {}", name());
        try {
            if (executed.get()) {
                return;
            }
            try (var ignored = unlockOnCompletion(context)) {
                LOGGER.debug("Processing task {}", name());
                // Run prerequisite tasks
                Set<String> directDependencies = new HashSet<>();
                for (TaskInput input : inputs()) {
                    directDependencies.addAll(input.dependencies());
                }
                for (String dependency : directDependencies) {
                    var task = context.getTask(dependency);
                    if (!task.isExecuted()) {
                        throw new IllegalStateException("Task `" + name + "` has not been executed but was requested");
                    }
                }
                var statePath = context.taskStatePath(this);
                try {
                    Files.createDirectories(statePath.getParent());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (context.useCached() && Files.exists(statePath)) {
                    try (var reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
                        JsonObject existingState = GSON.fromJson(reader, JsonObject.class);
                        JsonElement existingInputState = existingState.get("inputs");
                        var targetHashes = existingState.get("hashes").getAsJsonObject();
                        var lastExecutedJson = existingState.get("lastExecuted");
                        long lastExecuted = 0;
                        if (lastExecutedJson != null) {
                            lastExecuted = lastExecutedJson.getAsLong();
                        }
                        boolean allOutputsMatch = true;
                        for (var output : outputTypes().keySet()) {
                            var oldHashElement = targetHashes.get(output);
                            if (oldHashElement == null || !oldHashElement.isJsonPrimitive() || !oldHashElement.getAsJsonPrimitive().isString()) {
                                allOutputsMatch = false;
                                break;
                            }
                            var oldHash = oldHashElement.getAsString();
                            var outputPath = context.taskOutputPath(this, output);
                            if (!Files.exists(outputPath)) {
                                allOutputsMatch = false;
                                break;
                            }
                            var hash = HashUtils.hash(outputPath);
                            if (!hash.equals(oldHash)) {
                                allOutputsMatch = false;
                                break;
                            }
                        }
                        if (allOutputsMatch && upToDate(lastExecuted, context)) {
                            JsonElement newInputState = recordedValue(context);
                            if (newInputState.equals(existingInputState)) {
                                updateState(existingState, context);
                                executed.set(true);
                                LOGGER.info("Task `" + name + "` is up-to-date.");
                                return;
                            }
                        }
                    } catch (Exception e) {
                        // something went wrong -- let's log it, then keep going:
                        LOGGER.info("Up-to-date check for task `" + name + "` failed", e);
                    }
                }
                // Something was not up-to-date -- so we run everything
                LOGGER.info("Starting task `" + name + "`.");
                if (parallelism == null) {
                    run(context);
                } else {
                    context.lockManager().enforcedParallelism(context, parallelism, () -> run(context));
                }
                saveState(context);
                LOGGER.info("Finished task `" + name + "`.");
                executed.set(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Exception in task "+name(),e);
        }
    }

    private void updateState(JsonObject oldState, Context context) {
        oldState.add("lastAccessed", new JsonPrimitive(System.currentTimeMillis()));
        try (var writer = Files.newBufferedWriter(context.taskStatePath(this), StandardCharsets.UTF_8)) {
            GSON.toJson(oldState, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public final String type() {
        return type;
    }

    private void saveState(Context context) {
        var statePath = context.taskStatePath(this);
        var inputState = recordedValue(context);
        JsonObject outputHashes = new JsonObject();
        for (var output : outputTypes().keySet()) {
            var outputPath = context.taskOutputPath(this, output);
            if (Files.exists(outputPath)) {
                try {
                    var hash = HashUtils.hash(outputPath);
                    outputHashes.addProperty(output, hash);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException("Output file for `"+output+"`not found after task `"+name+"` completed");
            }
        }
        JsonObject state = new JsonObject();
        state.add("inputs", inputState);
        state.add("hashes", outputHashes);
        var currentTime = System.currentTimeMillis();
        state.add("lastAccessed", new JsonPrimitive(currentTime));
        state.add("lastExecuted", new JsonPrimitive(currentTime));
        try (var writer = Files.newBufferedWriter(statePath, StandardCharsets.UTF_8)) {
            GSON.toJson(state, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void hashReference(ByteConsumer digest, Context context) {
        if (referenceHash == null) {
            synchronized (this) {
                if (referenceHash == null) {
                    var stream = new ByteArrayOutputStream();
                    var consumer = ByteConsumer.of(stream);
                    consumer.update(type().getBytes(StandardCharsets.UTF_8));
                    consumer.update(((Integer) cacheVersion()).byteValue());
                    for (TaskInput input : inputs()) {
                        consumer.update(input.name().getBytes(StandardCharsets.UTF_8));
                        input.hashReference(consumer, context);
                    }
                    referenceHash = stream.toByteArray();
                }
            }
        }
        digest.update(referenceHash);
    }

    @Override
    public void hashContents(ByteConsumer digest, Context context) {
        if (contentsHash == null) {
            synchronized (this) {
                if (contentsHash == null) {
                    var stream = new ByteArrayOutputStream();
                    var consumer = ByteConsumer.of(stream);
                    consumer.update(type().getBytes(StandardCharsets.UTF_8));
                    consumer.update(((Integer) cacheVersion()).byteValue());
                    var sortedInputs = new ArrayList<>(inputs());
                    sortedInputs.sort(Comparator.comparing(TaskInput::name));
                    for (TaskInput input : sortedInputs) {
                        for (var dependency : input.dependencies()) {
                            if (!context.getTask(dependency).executed.get()) {
                                throw new IllegalStateException("Dependency `"+dependency+"` of task `"+name()+"` has not been executed but was requested");
                            }
                        }
                        consumer.update(input.name().getBytes(StandardCharsets.UTF_8));
                        input.hashContents(consumer, context);
                    }
                    contentsHash = stream.toByteArray();
                }
            }
        }
        digest.update(contentsHash);
    }

    @Override
    public JsonElement recordedValue(Context context) {
        JsonObject state = new JsonObject();
        state.addProperty("type", getClass().getName());
        state.addProperty("cacheVersion", cacheVersion());
        JsonObject inputs = new JsonObject();
        for (TaskInput input : inputs()) {
            JsonObject inputObject = new JsonObject();
            inputObject.add("value", input.recordedValue(context));
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                input.hashContents(ByteConsumer.of(digest), context);
                var hash = HexFormat.of().formatHex(digest.digest());
                inputObject.addProperty("key", hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            inputs.add(input.name(), inputObject);
        }
        state.add("inputs", inputs);
        JsonObject outputs = new JsonObject();
        for (var output : outputTypes().entrySet()) {
            outputs.addProperty(output.getKey(), output.getValue());
        }
        state.add("outputs", outputs);
        return state;
    }

    protected boolean upToDate(long lastExecuted, Context context) {
        return true;
    }

    public static Task task(TaskModel model, WorkItem workItem, Context context) {
        return switch (model) {
            case TaskModel.DownloadManifest downloadManifest -> new DownloadManifestTask(downloadManifest);
            case TaskModel.DownloadJson downloadJson -> new DownloadJsonTask(downloadJson, workItem, context);
            case TaskModel.DownloadDistribution downloadDistribution -> new DownloadDistributionTask(downloadDistribution, workItem, context);
            case TaskModel.DownloadMappings downloadMappings -> new DownloadMappingsTask(downloadMappings, workItem, context);
            case TaskModel.SplitClassesResources splitClassesResources -> new SplitClassesResourcesTask(splitClassesResources, workItem, context);
            case TaskModel.Tool tool -> new ToolTask(tool, workItem, context);
            case TaskModel.ListClasspath listClasspath -> new ListClasspathTask(listClasspath, workItem, context);
            case TaskModel.InjectSources injectSources -> new InjectSourcesTask(injectSources, workItem, context);
            case TaskModel.PatchSources patchSources -> new PatchSourcesTask(patchSources, workItem, context);
            case TaskModel.RetrieveData retrieveData -> new RetrieveDataTask(retrieveData, workItem, context);
            case TaskModel.Compile compile -> new CompileTask(compile, workItem, context);
            case TaskModel.InterfaceInjection interfaceInjection -> new InterfaceInjectionTask(interfaceInjection, workItem, context);
            case TaskModel.Jst jst -> new JstTask(jst, workItem, context);
            case TaskModel.DownloadAssets downloadAssets ->  new DownloadAssetsTask(downloadAssets, workItem, context);
            case TaskModel.TransformMappings transformMappings -> new TransformMappingsTask(transformMappings, workItem, context);
        };
    }

    protected abstract void run(Context context);
}
