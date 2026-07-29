package guru.kumo.operator.channel;

import guru.kumo.operator.command.OperatorShellCommand;
import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.tool.TodoWriteTool;
import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@Profile("operator")
public class ConsoleChannel implements Runnable, Channel {
    private static final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final Terminal terminal;
    private final AgentOperatorService agentOperatorService;

    public ConsoleChannel(Terminal terminal, AgentOperatorService agentOperatorService) {
        this.terminal = terminal;
        this.agentOperatorService = agentOperatorService;
        start(new Thread(this), agentOperatorService);
    }

    @Override
    public void run() {
        AtomicBoolean isTerminating = new AtomicBoolean(false);
        terminal.handle(Terminal.Signal.INT, signal -> {
            if (!isTerminating.get()) {
                isTerminating.set(true);
            }
        });
        try {
            String conversationId = OperatorShellCommand.conversationId;
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            StringBuilder buffer = new StringBuilder();
            boolean collecting = false;
            while (!isTerminating.get()) {
                discardPendingInput();
                updateView(String.format("%s", ColorEnum.BLUE_BOLD_BRIGHT));
                String nextLine = reader.readLine(collecting ? "" : String.format("%nWaiting for input, type an empty line to finish:%n"));
                if (nextLine == null) break; // EOF (e.g. Ctrl+D)
                String trimmed = nextLine.trim();
                if (!collecting) {
                    if (trimmed.isEmpty()) continue;
                    // Start collecting a new (possibly multi-line) message
                    buffer.setLength(0);
                    buffer.append(trimmed.stripLeading());
                    collecting = true;
                } else {
                    if (trimmed.isEmpty()) {
                        updateView(String.format("%sProcessing ...%s%n", ColorEnum.GREEN_BOLD_BRIGHT, ColorEnum.RESET));
                        // Blank line = end of message, send it
                        try {
                            UserMessage userMessage = UserMessage.builder().text(buffer.toString()).build();
                            agentOperatorService.processCall("[OPERATOR]", conversationId, List.of(userMessage));
                        } catch (Exception e) {
                            if (log.isDebugEnabled()) {
                                log.error("Failed to process chat message", e);
                            } else {
                                log.error("Failed to process chat message: {}", e.getMessage());
                            }
                        }
                        collecting = false;
                    } else {
                        // Keep appending lines to the current message
                        buffer.append(System.lineSeparator()).append(nextLine);
                    }
                }
            }
        } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException ignored) {
        }
    }

    private void discardPendingInput() {
        try {
            int available = System.in.available();
            if (available > 0) {
                System.in.read(new byte[available]);
            }
        } catch (IOException ignored) {
        }
    }

    private void updateView(String message) {
        System.out.println(message);
    }

    @Override
    public void prefillOutput(List<Message> messageList) {
        for (Message message : messageList) {
            switch (message.getMessageType()) {
                case SYSTEM ->
                        updateView(String.format("%s[PREFILL][SYSTEM]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, message.getText(), ColorEnum.RESET));
                case USER ->
                        updateView(String.format("%s[PREFILL][USER]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, message.getText(), ColorEnum.RESET));
            }
        }
    }

    @Override
    public void agent(String logPrefix, ChatResponse chatResponse) {
        if (chatResponse == null) return;
        if (chatResponse.getResult().getOutput().getMetadata().containsKey("reasoningContent")) {
            updateView(String.format("%s%s REASONING:[%n%s]%s%n", ColorEnum.YELLOW_BOLD_BRIGHT, logPrefix, chatResponse.getResult().getOutput().getMetadata().get("reasoningContent"), ColorEnum.RESET));
        }
        updateView(String.format("%s%s ASSISTANT:[%n%s%n]%s%n", ColorEnum.GREEN_BOLD_BRIGHT, logPrefix, chatResponse.getResult().getOutput().getText(), ColorEnum.RESET));
        updateView(String.format("%s%s %s%s%n", ColorEnum.GREEN, logPrefix, jsonMapper.writeValueAsString(chatResponse.getMetadata().getRateLimit()), ColorEnum.RESET));
        updateView(String.format("%s%s %s%s%n%n", ColorEnum.GREEN, logPrefix, chatResponse.getMetadata().getUsage(), ColorEnum.RESET));
    }

    @Override
    public void subagent(TaskCall taskCall, SystemMessage systemMessage, UserMessage userMessage) {
        updateView(String.format("%s[%s][SYSTEM]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, taskCall.subagent_type(), systemMessage.getText(), ColorEnum.RESET));
        updateView(String.format("%s[%s][USER]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, taskCall.subagent_type(), userMessage.getText(), ColorEnum.RESET));
    }

    @Override
    public void todos(TodoWriteTool.Todos event) {
        List<TodoWriteTool.Todos.TodoItem> todos = event.todos();
        int completed = (int) todos.stream().filter(t -> t.status() == TodoWriteTool.Todos.Status.completed).count();
        int total = todos.size();

        updateView(String.format("\n%sProgress: %d/%d tasks completed (%.0f%%)%s\n", ColorEnum.GREEN_BOLD_BRIGHT, completed, total, (completed * 100.0 / total), ColorEnum.RESET));

        for (TodoWriteTool.Todos.TodoItem item : todos) {
            String statusIcon = switch (item.status()) {
                case completed -> "[✓]";
                case in_progress -> "[→]";
                case pending -> "[ ]";
            };
            updateView(String.format("%s  %s %s%s\n", ColorEnum.GREEN_BOLD_BRIGHT, statusIcon, item.content(), ColorEnum.RESET));
        }
    }

    @Override
    public void toolCallToString(String logPrefix, AssistantMessage.ToolCall toolCall) {
        updateView(String.format("%s%s TollCall[id=%s, type=%s, name=%s, arguments={%s}]%s", ColorEnum.CYAN, logPrefix, toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments().substring(0, Math.min(132, toolCall.arguments().length())), ColorEnum.RESET));
    }

    @Override
    public void toolResponseToString(String logPrefix, ToolResponseMessage.ToolResponse toolResponse) {
        updateView(String.format("%s%s ToolResponse[id=%s, name=%s, responseData=%s]%s", ColorEnum.MAGENTA, logPrefix, toolResponse.id(), toolResponse.name(), toolResponse.responseData().substring(0, Math.min(132, toolResponse.responseData().length())), ColorEnum.RESET));
    }

    @Override
    public void shutdown() {
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
    }
}
