package guru.kumo.operator.channel;

import guru.kumo.operator.model.BashInput;
import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.service.ChatMemoryService;
import guru.kumo.operator.tool.*;
import guru.kumo.operator.util.ColorEnum;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@Profile("operator")
public class ConsoleChannel extends Thread implements Channel {
    private static final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final Terminal terminal;
    private final AgentOperatorService agentOperatorService;
    private final ChatMemoryService chatMemoryService;
    @Setter
    private String conversationId;

    public ConsoleChannel(Terminal terminal, AgentOperatorService agentOperatorService, ChatMemoryService chatMemoryService) {
        this.terminal = terminal;
        this.agentOperatorService = agentOperatorService;
        this.chatMemoryService = chatMemoryService;
        this.conversationId = chatMemoryService.createConversationId();
        agentOperatorService.subscribe().subscribe(agentResponse -> {
            switch (agentResponse.getType()) {
                case INIT, CONSOLE, DISCORD, TELEGRAM ->
                        consoleOutput(agentResponse.getLogPrefix(), agentResponse.getMessageList());
                case AGENT -> agent(agentResponse.getLogPrefix(), agentResponse.getChatResponse());
                case SUBAGENT ->
                        subagent(agentResponse.getLogPrefix(), agentResponse.getSystemMessage(), agentResponse.getUserMessage());
                case TODO -> todos(agentResponse.getTodos());
                case TOOL_CALL -> toolCallToString(agentResponse.getLogPrefix(), agentResponse.getToolCall());
                case TOOL_RESPONSE ->
                        toolResponseToString(agentResponse.getLogPrefix(), agentResponse.getToolResponse());
                case TOOL_CALLING_OPTIONS ->
                        toolCallingOptions(agentResponse.getLogPrefix(), agentResponse.getToolCallingChatOptions());
            }
        });
    }

    @Override
    public void shutdown() {
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
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
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            StringBuilder buffer = new StringBuilder();
            boolean collecting = false;
            while (!isTerminating.get()) {
                discardPendingInput();
                String nextLine = reader.readLine(collecting ? "" : String.format("%n%sWaiting for input, type an empty line to finish:%s%n%n", ColorEnum.BOLD_HIGH_INTENSITY_BLUE, ColorEnum.RESET));
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
                        // Blank line = end of message, send it
                        try {
                            if (buffer.toString().equalsIgnoreCase("/ResetContextWindow")) {
                                conversationId = chatMemoryService.createConversationId();
                                terminal.output().write("Done.".getBytes(StandardCharsets.UTF_8));
                            } else {
                                UserMessage userMessage = UserMessage.builder().text(buffer.toString()).build();
                                agentOperatorService.processConsoleRequest(conversationId, List.of(userMessage));
                            }
                        } catch (Exception e) {
                            log.error("Failed to process chat message", e);
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

    private void consoleOutput(String logPrefix, List<Message> messageList) {
        for (Message message : messageList) {
            switch (message.getMessageType()) {
                case SYSTEM ->
                        updateView(String.format("%s%s[SYSTEM]:[%s]%s", logPrefix, ColorEnum.ORANGE, message.getText(), ColorEnum.RESET));
                case USER ->
                        updateView(String.format("%s%s[USER]:[%s]%s", logPrefix, ColorEnum.BOLD_CYAN, message.getText(), ColorEnum.RESET));
            }
        }
    }

    private void subagent(String logPrefix, SystemMessage systemMessage, UserMessage userMessage) {
        updateView(String.format("%s%s[SYSTEM]:[%n%s%n]%s%n", logPrefix, ColorEnum.ORANGE, systemMessage.getText(), ColorEnum.RESET));
        updateView(String.format("%s%s[USER]:[%n%s%n]%s%n", logPrefix, ColorEnum.BOLD_CYAN, userMessage.getText(), ColorEnum.RESET));
    }

    private void agent(String logPrefix, ChatResponse chatResponse) {
        if (chatResponse == null) return;
        if (chatResponse.getResult().getOutput().getMetadata().containsKey("reasoningContent")) {
            updateView(String.format("%s%sREASONING:[%s]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_YELLOW, chatResponse.getResult().getOutput().getMetadata().get("reasoningContent").toString().trim(), ColorEnum.RESET));
        }
        updateView(String.format("%s%sASSISTANT:[%s]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_GREEN, chatResponse.getResult().getOutput().getText(), ColorEnum.RESET));
        updateView(String.format("%s%s%s%s", logPrefix, ColorEnum.GREEN, rateLimitToString(chatResponse.getMetadata().getRateLimit()), ColorEnum.RESET));
        updateView(String.format("%s%s%s%s", logPrefix, ColorEnum.GREEN, useageToString(chatResponse.getMetadata().getUsage()), ColorEnum.RESET));
        updateView(String.format("%s%sFinish Reason:[%s%s%s] %s %s%s%n%n", logPrefix, ColorEnum.GREEN, ColorEnum.PURPLE, chatResponse.getResult().getOutput().getMetadata().get("finishReason"), ColorEnum.GREEN, LocalDateTime.now().toLocalTime(), LocalDateTime.now().toLocalDate(), ColorEnum.RESET));
    }

    private String rateLimitToString(RateLimit rateLimit) {
        if (rateLimit == null) return "";
        return String.format("RateLimit:[Request:[Limit=%d, Remaining=%d, Reset=%s] Token:[Limit=%d, Remaining=%d, Reset=%s]]",
                rateLimit.getRequestsLimit(), rateLimit.getRequestsRemaining(), rateLimit.getRequestsReset(),
                rateLimit.getTokensLimit(), rateLimit.getTokensRemaining(), rateLimit.getTokensReset());
    }

    private String useageToString(Usage usage) {
        if (usage == null) return "";
        return String.format("Usage:[PromptTokens=%d, CompletionTokens=%d, TotalTokens=%s%d%s, CacheReadInputTokens=%d, CacheWriteInputTokens=%d]",
                usage.getPromptTokens(), usage.getCompletionTokens(), ColorEnum.PURPLE, usage.getTotalTokens(), ColorEnum.GREEN, usage.getCacheReadInputTokens(), usage.getCacheWriteInputTokens());
    }

    private void toolCallToString(String logPrefix, AssistantMessage.ToolCall toolCall) {
        if (toolCall == null) return;

        switch (toolCall.name()) {
            case TaskTool.TOOL_NAME -> {
                TaskCall taskCallInput = jsonMapper.readValue(toolCall.arguments(), TaskCall.class);
                updateView(String.format("%s%sTollCall[name=%s, description={%s}, run_in_background=%b]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_BLUE, ColorEnum.PURPLE + toolCall.name() + ColorEnum.BOLD_HIGH_INTENSITY_BLUE, taskCallInput.description(), taskCallInput.run_in_background(), ColorEnum.RESET));
            }
            case SkillsTool.TOOL_NAME -> {
                SkillsTool.SkillsInput skillsInput = jsonMapper.readValue(toolCall.arguments(), SkillsTool.SkillsInput.class);
                updateView(String.format("%s%sTollCall[name=%s, command={%s}]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_BLUE, ColorEnum.PURPLE + toolCall.name() + ColorEnum.BOLD_HIGH_INTENSITY_BLUE, skillsInput.command(), ColorEnum.RESET));
            }
            case ShellTools.TOOL_NAME -> {
                BashInput bashInput = jsonMapper.readValue(toolCall.arguments(), BashInput.class);
                updateView(String.format("%s%sTollCall[name=%s, description=%s, arguments={%s}, run_in_background=%b]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_BLUE, ColorEnum.PURPLE + toolCall.name() + ColorEnum.BOLD_HIGH_INTENSITY_BLUE, bashInput.description(), ColorEnum.PURPLE + bashInput.command() + ColorEnum.BOLD_HIGH_INTENSITY_BLUE, bashInput.runInBackground(), ColorEnum.RESET));
            }
            default ->
                    updateView(String.format("%s%sTollCall[name=%s, arguments={%s}]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_BLUE, ColorEnum.PURPLE + toolCall.name() + ColorEnum.BOLD_HIGH_INTENSITY_BLUE, toolCall.arguments().substring(0, Math.min(132, toolCall.arguments().length())), ColorEnum.RESET));
        }
    }

    private void toolResponseToString(String logPrefix, ToolResponseMessage.ToolResponse toolResponse) {
        updateView(String.format("%s%sToolResponse[name=%s, responseData=%s]%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_BLUE, ColorEnum.PURPLE + toolResponse.name() + ColorEnum.BOLD_HIGH_INTENSITY_BLUE, toolResponse.responseData().substring(0, Math.min(132, toolResponse.responseData().length())), ColorEnum.RESET));
    }

    private void toolCallingOptions(String logPrefix, ToolCallingChatOptions toolCallingChatOptions) {
        updateView(String.format("%s%sToolCallingOptions: %s%s", logPrefix, ColorEnum.BOLD_HIGH_INTENSITY_BLUE, toolCallingChatOptions.getToolCallbacks().stream().map(x -> x.getToolDefinition().name()).toList(), ColorEnum.RESET));
    }

    private void todos(TodoWriteTool.Todos event) {
        List<TodoWriteTool.Todos.TodoItem> todos = event.todos();
        int completed = (int) todos.stream().filter(t -> t.status() == TodoWriteTool.Todos.Status.completed).count();
        int total = todos.size();

        updateView(String.format("\n%sProgress: %d/%d tasks completed (%.0f%%)%s\n", ColorEnum.BOLD_HIGH_INTENSITY_GREEN, completed, total, (completed * 100.0 / total), ColorEnum.RESET));

        for (TodoWriteTool.Todos.TodoItem item : todos) {
            String statusIcon = switch (item.status()) {
                case completed -> "[✓]";
                case in_progress -> "[→]";
                case pending -> "[ ]";
            };
            updateView(String.format("%s  %s %s%s\n", ColorEnum.BOLD_HIGH_INTENSITY_GREEN, statusIcon, item.content(), ColorEnum.RESET));
        }
    }
}
