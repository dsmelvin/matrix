package guru.kumo.operator.tool;

import guru.kumo.operator.util.ColorEnum;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TodoProgressListener {
    @EventListener
    public void onTodoUpdate(TodoUpdateEvent event) {
        int completed = (int) event.getTodos().stream().filter(t -> t.status() == TodoWriteTool.Todos.Status.completed).count();
        int total = event.getTodos().size();

        System.out.printf("\n%sProgress: %d/%d tasks completed (%.0f%%)%s\n", ColorEnum.GREEN_BOLD_BRIGHT, completed, total, (completed * 100.0 / total), ColorEnum.RESET);

        for (TodoWriteTool.Todos.TodoItem item : event.getTodos()) {
            String statusIcon = switch (item.status()) {
                case completed -> "[✓]";
                case in_progress -> "[→]";
                case pending -> "[ ]";
            };
            System.out.printf("%s  %s %s%s\n", ColorEnum.GREEN_BOLD_BRIGHT, statusIcon, item.content(), ColorEnum.RESET);
        }
    }
}
