package guru.kumo.operator.tool;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class DefaultTaskRepository implements TaskRepository {
    private final Map<String, BackgroundTask> backgroundTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public DefaultTaskRepository() {
        this(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("background-task-" + thread.getId());
            return thread;
        }), true);
    }

    public DefaultTaskRepository(ExecutorService executor) {
        this(executor, false);
    }

    public DefaultTaskRepository(ExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public BackgroundTask getTasks(String taskId) {
        return this.backgroundTasks.get(taskId);
    }

    @Override
    public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
        var future = CompletableFuture.supplyAsync(taskExecution, this.executor);
        BackgroundTask backgroundTask = new BackgroundTask(taskId, future);
        this.backgroundTasks.put(taskId, backgroundTask);
        return backgroundTask;
    }

    @Override
    public void removeTask(String taskId) {
        this.backgroundTasks.remove(taskId);
    }

    @Override
    public void clear() {
        this.backgroundTasks.clear();
    }

    public void clearCompletedTasks() {
        this.backgroundTasks.entrySet().removeIf(entry -> entry.getValue().isCompleted());
    }

    public void shutdown() {
        if (this.ownsExecutor && this.executor != null) {
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
