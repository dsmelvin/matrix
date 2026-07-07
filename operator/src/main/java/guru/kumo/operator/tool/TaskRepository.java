package guru.kumo.operator.tool;

import java.util.function.Supplier;

public interface TaskRepository {
    /**
     * Get a background task by its ID.
     *
     * @param taskId the task identifier
     * @return the background task, or null if not found
     */
    BackgroundTask getTasks(String taskId);

    /**
     * Add a new background task to the repository.
     *
     * @param taskId        the task identifier
     * @param taskExecution the supplier that executes the task and returns its output
     * @return the created background task
     */
    BackgroundTask putTask(String taskId, Supplier<String> taskExecution);

    /**
     * Remove a background task from the repository.
     *
     * @param taskId the task identifier
     */
    void removeTask(String taskId);

    /**
     * Clear all tasks from the repository.
     */
    void clear();
}
