package guru.kumo.operator.command;

import guru.kumo.operator.service.OperatorService;
import guru.kumo.operator.tool.ImageReaderTool;
import guru.kumo.operator.tool.TodoUpdateEvent;
import guru.kumo.operator.tool.TodoWriteTool;
import guru.kumo.operator.util.ColorEnum;
import guru.kumo.operator.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Profile("operator")
public class OperatorShellCommand {
    public static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();
    private static final ArrayList<ToolCallback> agentTools = new ArrayList<>();

    private final ChatMemory chatMemory;
    private final OpenAiChatModel chatModel;
    private final String conversationId;
    private final Terminal terminal;
    private final Resource operatorSystemPrompt;
    private final String sessionMemoryPathName;
    private final SystemMessage systemMessage;
    private final OperatorService operatorService;

    OperatorShellCommand(Terminal terminal,
                         OpenAiChatModel openAiChatModel,
                         ApplicationEventPublisher applicationEventPublisher,
                         OperatorService operatorService,
                         @Value("${agent.tools}") String[] agentToolList,
                         @Value("${agent.prompt.system}") String operatorSystemPrompt,
                         @Value("${agent.path.memory}") String sessionMemoryPathName,
                         @Value("${agent.paths.agents}") List<String> agentPaths,
                         @Value("${agent.paths.skills}") List<String> skillPaths,
                         @Value("${agent.message-window-chat-memory.max-messages}") int maxMessages) {
        this.terminal = terminal;
        this.chatModel = openAiChatModel;
        this.operatorService = operatorService;
        this.conversationId = UUID.randomUUID().toString();
        this.operatorSystemPrompt = new FileSystemResource(operatorSystemPrompt);

        this.sessionMemoryPathName = sessionMemoryPathName;
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        this.systemMessage = loadSystemPrompt();

        List<Resource> skillResources = loadSkills(skillPaths);
        List<Resource> agentTasksResources = loadAgentTasks(agentPaths);
        agentTools.addAll(loadTools(applicationEventPublisher, agentToolList, skillResources, agentTasksResources));
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
                            operatorService.processCall("[OPERATOR]", chatModel, agentTools, chatMemory, conversationId, List.of(userMessage));
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
                    operatorService.processCall("[OPERATOR]", chatModel, agentTools, chatMemory, conversationId, List.of(userMessage));
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

    private List<Resource> loadSkills(List<String> skillPaths) {
        List<Resource> skillResources = new ArrayList<>();
        if (skillPaths == null || skillPaths.isEmpty()) return skillResources;
        skillPaths.forEach(skillPath -> {
            FileSystemResource resource = new FileSystemResource(skillPath);
            if (Utils.containsFile(resource.getFile(), "SKILL.md")) {
                skillResources.add(resource);
            }
        });
        return skillResources;
    }

    private List<Resource> loadAgentTasks(List<String> agentPaths) {
        List<Resource> agentTasksResources = new ArrayList<>();
        if (agentPaths == null || agentPaths.isEmpty()) return agentTasksResources;
        agentPaths.forEach(agentPath -> {
            FileSystemResource resource = new FileSystemResource(agentPath);
            if (resource.exists() && resource.isFile()) {
                agentTasksResources.add(resource);
            }
        });
        return agentTasksResources;
    }

    private ArrayList<ToolCallback> loadTools(ApplicationEventPublisher applicationEventPublisher, String[] agentToolList, List<Resource> skillResources, List<Resource> agentTasksResources) {
        List<ToolCallback[]> toolList = new ArrayList<>();
        for (String at : agentToolList) {
            switch (at) {
                case "TaskTool" -> {
                    if (!agentTasksResources.isEmpty()) {
                        agentTools.add(TaskTool.builder().taskRepository(new DefaultTaskRepository())
                                .subagentReferences(ClaudeSubagentReferences.fromResources(agentTasksResources))
                                .subagentTypes(ClaudeSubagentType.builder()
                                        .chatModel(chatModel).agentTools(agentTools).skillResources(skillResources).operatorService(operatorService).build()).build());
                        log.info("TaskTool loaded successfully");
                    }
                }
                case "SkillTool" -> {
                    if (!skillResources.isEmpty()) {
                        agentTools.add(SkillsTool.builder().addSkillsResources(skillResources).build());
                        log.info("SkillTool loaded successfully");
                    }
                }
                case "ImageReaderTool" -> {
                    toolList.add(ToolCallbacks.from(ImageReaderTool.builder().build()));
                    log.info("ImageReaderTool loaded successfully");
                }
                case "GrepTool" -> {
                    toolList.add(ToolCallbacks.from(GrepTool.builder().build()));
                    log.info("GrepTool loaded successfully");
                }
                case "GlobTool" -> {
                    toolList.add(ToolCallbacks.from(GlobTool.builder().build()));
                    log.info("GlobTool loaded successfully");
                }
                case "ShellTools" -> {
                    toolList.add(ToolCallbacks.from(ShellTools.builder().build()));
                    log.info("ShellTools loaded successfully");
                }
                case "FileSystemTools" -> {
                    toolList.add(ToolCallbacks.from(FileSystemTools.builder().build()));
                    log.info("FileSystemTools loaded successfully");
                }
                case "ListDirectoryTool" -> {
                    toolList.add(ToolCallbacks.from(ListDirectoryTool.builder().build()));
                    log.info("ListDirectoryTool loaded successfully");
                }
                case "SmartWebFetchTool" -> {
                    toolList.add(ToolCallbacks.from(SmartWebFetchTool.builder(ChatClient.create(chatModel)).build()));
                    log.info("SmartWebFetchTool loaded successfully");
                }
                case "TodoWriteTool" -> {
                    toolList.add(ToolCallbacks.from(TodoWriteTool.builder().todoEventHandler(event -> applicationEventPublisher.publishEvent(new TodoUpdateEvent(this, event.todos()))).build()));
                    log.info("TodoWriteTool loaded successfully");
                }
            }
        }
        return new ArrayList<>(Arrays.asList(toolList.stream().flatMap(Arrays::stream).toArray(ToolCallback[]::new)));
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
