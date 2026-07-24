package guru.kumo.operator.command;

import guru.kumo.operator.service.ChatMemoryService;
import guru.kumo.operator.service.OperatorService;
import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Profile("operator")
public class OperatorShellCommand {
    private final Terminal terminal;
    private final String conversationId;
    private final SystemMessage systemMessage;
    private final OperatorService operatorService;
    private final ChatMemoryService chatMemoryService;

    OperatorShellCommand(Terminal terminal, OperatorService operatorService, ChatMemoryService chatMemoryService) {
        this.terminal = terminal;
        this.operatorService = operatorService;
        this.chatMemoryService = chatMemoryService;
        this.conversationId = UUID.randomUUID().toString();
        this.systemMessage = chatMemoryService.loadSystemPrompt(conversationId);
    }

    @Command(name = {"operator"})
    public void run(
            @Option(longName = "prompt-file", shortName = 'p', required = false, description = "To preload a prompt file")
            String promptFileName,
            @Option(longName = "session-memory-file", shortName = 'm', required = false, description = "The history of chat memory file")
            String savedSessionMemoryFileName,
            @Option(longName = "save-session-memory", shortName = 's', required = false, description = "flag of storing session memory into a file")
            boolean saveSessionMemory) {
        AtomicBoolean isTerminating = new AtomicBoolean(false);
        terminal.handle(Terminal.Signal.INT, signal -> {
            if (!isTerminating.get()) {
                isTerminating.set(true);
                chatMemoryService.shutdown(conversationId, saveSessionMemory);
                operatorService.shutdown();
            }
        });

        try {
            chatMemoryService.loadSavedSessionMemoryFile(conversationId, savedSessionMemoryFileName);
            loadPromptFile(promptFileName);
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            StringBuilder buffer = new StringBuilder();
            boolean collecting = false;
            while (!isTerminating.get()) {
                discardPendingInput();
                System.out.printf("%s", ColorEnum.BLUE_BOLD_BRIGHT);
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
                        System.out.printf("%sProcessing ...%s%n", ColorEnum.GREEN_BOLD_BRIGHT, ColorEnum.RESET);
                        // Blank line = end of message, send it
                        try {
                            UserMessage userMessage = UserMessage.builder().text(buffer.toString()).build();
                            System.out.printf("%s[PREFILL][USER]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, userMessage.getText(), ColorEnum.RESET);
                            operatorService.processCall("[OPERATOR]", conversationId, List.of(userMessage));
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
        } finally {
            chatMemoryService.shutdown(conversationId, saveSessionMemory);
            operatorService.shutdown();
        }
        System.out.printf("%sSession Closed.%s%n", ColorEnum.GREEN_BOLD_BRIGHT, ColorEnum.RESET);
    }

    private void loadPromptFile(String promptFileName) {
        try {
            if (promptFileName == null) return;
            File promptFile = new File(promptFileName);
            if (promptFile.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(promptFile)) {
                    log.info("Prompt file loaded successfully. {}", promptFile.getAbsolutePath());
                    UserMessage userMessage = UserMessage.builder().text(StreamUtils.copyToString(fileInputStream, StandardCharsets.UTF_8)).build();
                    System.out.printf("%s[PREFILL][USER]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, userMessage.getText(), ColorEnum.RESET);
                    operatorService.processCall("[OPERATOR]", conversationId, List.of(userMessage));
                }
            }
        } catch (IOException e) {
            log.error("Error reading Prompt file", e);
            throw new RuntimeException(e);
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
}
