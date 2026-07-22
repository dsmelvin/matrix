package guru.kumo.operator.command;

import guru.kumo.operator.service.OperatorService;
import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Profile("operator")
public class OperatorShellCommand {
    public static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();

    private final Terminal terminal;
    private final String conversationId;
    private final ChatMemory chatMemory;
    private final SystemMessage systemMessage;
    private final String sessionMemoryPathName;
    private final Resource operatorSystemPrompt;
    private final OperatorService operatorService;

    OperatorShellCommand(Terminal terminal,
                         OperatorService operatorService,
                         @Value("${agent.prompt.system}") String operatorSystemPrompt,
                         @Value("${agent.path.memory}") String sessionMemoryPathName,
                         @Value("${agent.message-window-chat-memory.max-messages}") int maxChatMemoryMessages) {
        this.terminal = terminal;
        this.operatorService = operatorService;
        this.conversationId = UUID.randomUUID().toString();
        this.operatorSystemPrompt = new FileSystemResource(operatorSystemPrompt);
        this.sessionMemoryPathName = sessionMemoryPathName;
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(maxChatMemoryMessages).build();
        this.systemMessage = loadSystemPrompt();
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
                saveSessionMemoryFile(saveSessionMemory);
            }
        });

        if (savedSessionMemoryFileName != null) {
            loadSavedSessionMemoryFile(savedSessionMemoryFileName);
        } else if (systemMessage != null && systemMessage.getText() != null) {
            chatMemory.add(conversationId, systemMessage);
            log.info("System Message loaded successfully");
            System.out.printf("%s[PREFILL][SYSTEM]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, systemMessage.getText(), ColorEnum.RESET);
        }

        try {
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
                            operatorService.processCall("[OPERATOR]", chatMemory, conversationId, List.of(userMessage));
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
            saveSessionMemoryFile(saveSessionMemory);
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
                    operatorService.processCall("[OPERATOR]", chatMemory, conversationId, List.of(userMessage));
                }
            }
        } catch (IOException e) {
            log.error("Error reading Prompt file", e);
            throw new RuntimeException(e);
        }
    }

    private void saveSessionMemoryFile(boolean saveSessionMemory) {
        File sessionMemoryFile = saveSessionMemory ? createSessionMemoryFile() : null;
        if (saveSessionMemory && sessionMemoryFile != null && sessionMemoryFile.exists()) {
            try (FileOutputStream fileOutputStream = new FileOutputStream(sessionMemoryFile)) {
                StreamUtils.copy(chatMessageListCodec.serialize(chatMemory.get(conversationId)).getBytes(), fileOutputStream);
                fileOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
                fileOutputStream.flush();
                log.info("Session memory file saved successfully. {}", sessionMemoryFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("Session memory file could not be saved.", e);
            }
        }
    }

    private void loadSavedSessionMemoryFile(String savedSessionMemoryFileName) {
        try {
            File savedSessionMemoryFile = new File(savedSessionMemoryFileName);
            if (savedSessionMemoryFile.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(savedSessionMemoryFile)) {
                    List<Message> messageList = chatMessageListCodec.deserialize(StreamUtils.copyToString(fileInputStream, StandardCharsets.UTF_8));
                    chatMemory.add(conversationId, messageList);
                    log.info("Session memory loaded successfully.");
                }
            } else {
                log.error("Session memory doesn't exist. {}", savedSessionMemoryFileName);
            }
        } catch (IOException e) {
            log.error("Error reading session memory file", e);
            throw new RuntimeException(e);
        }
    }

    private File createSessionMemoryFile() {
        File sessionMemoryFile = null;
        try {
            if (StringUtils.hasLength(sessionMemoryPathName)) {
                File sessionMemoryPath = new File(sessionMemoryPathName);
                if (sessionMemoryPath.exists() && sessionMemoryPath.isDirectory()) {
                    LocalDateTime now = LocalDateTime.now();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
                    String timestamp = now.format(formatter);
                    String fileName = sessionMemoryPath.getAbsolutePath() + "/session-memory-" + timestamp + ".json";
                    sessionMemoryFile = new File(fileName);
                    if (sessionMemoryFile.exists() || sessionMemoryFile.createNewFile()) {
                        log.info("Session memory file opened. {}", fileName);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Session memory file could not be created.", e);
            throw new RuntimeException(e);
        }
        return sessionMemoryFile;
    }

    private SystemMessage loadSystemPrompt() {
        if (operatorSystemPrompt != null && operatorSystemPrompt.exists() && operatorSystemPrompt.isFile()) {
            String workingDirectory = System.getProperty("user.dir");
            String platform = System.getProperty("os.name").toLowerCase();
            String osVersion = System.getProperty("os.name") + " " + System.getProperty("os.version");
            String todayDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

            HashMap<String, Object> systemPromptEnvMap = new HashMap<>();
            systemPromptEnvMap.put("WorkingDirectory", workingDirectory);
            systemPromptEnvMap.put("Platform", platform);
            systemPromptEnvMap.put("OSVersion", osVersion);
            systemPromptEnvMap.put("Today", todayDate);
            systemPromptEnvMap.put("MEMORIES_ROOT_DIERCTORY", sessionMemoryPathName == null ? "NONE" : sessionMemoryPathName);
            systemPromptEnvMap.put("OSShell", System.getenv("SHELL") == null ? "UNKNOWN" : System.getenv("SHELL"));

            PromptTemplate systemTemplate = new PromptTemplate(operatorSystemPrompt);
            log.info("System Message Environment Variables: {}", systemPromptEnvMap);
            return SystemMessage.builder().text(systemTemplate.render(systemPromptEnvMap)).build();
        }
        return null;
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
