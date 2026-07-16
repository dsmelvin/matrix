package guru.kumo.operator.command;

import guru.kumo.operator.advisor.ImageReaderToolResponseAdvisor;
import guru.kumo.operator.advisor.MyLoggingAdvisor;
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
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private final ChatMemory chatMemory;
    private final OpenAiChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final String conversationId;
    private final Terminal terminal;
    private final ResourceLoader resourceLoader;
    private final Resource operatorSystemPrompt;
    private final String sessionMemoryPathName;
    private final Integer maxCompletionTokens;
    private final JsonMapper jsonMapper;
    private final SystemMessage systemMessage;
    private final List<ToolCallback> agentTools = new ArrayList<>();
    private final List<Resource> skillResources = new ArrayList<>();
    private final List<Resource> AgentTasksResources = new ArrayList<>();

    OperatorShellCommand(Terminal terminal,
                         ResourceLoader resourceLoader,
                         OpenAiChatModel openAiChatModel,
                         ApplicationEventPublisher applicationEventPublisher,
                         @Value("${agent.tools}") String[] agentToolList,
                         @Value("${agent.prompt.system}") String operatorSystemPrompt,
                         @Value("${agent.path.memory}") String sessionMemoryPathName,
                         @Value("${agent.paths.agents}") List<String> agentPaths,
                         @Value("${agent.paths.skills}") List<String> skillPaths,
                         @Value("${agent.message-window-chat-memory.max-messages}") int maxMessages,
                         @Value("${agent.chat-model.max-completion-tokens}") Integer maxCompletionTokens) {
        this.terminal = terminal;
        this.resourceLoader = resourceLoader;
        this.chatModel = openAiChatModel;
        this.conversationId = UUID.randomUUID().toString();
        this.maxCompletionTokens = maxCompletionTokens;
        this.jsonMapper = JsonMapper.builder().build();

        if (operatorSystemPrompt.startsWith("/")) {
            this.operatorSystemPrompt = new FileSystemResource(operatorSystemPrompt);
        } else if (!operatorSystemPrompt.isEmpty()) {
            this.operatorSystemPrompt = resourceLoader.getResource(operatorSystemPrompt);
        } else {
            this.operatorSystemPrompt = null;
        }

        this.sessionMemoryPathName = sessionMemoryPathName;
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.systemMessage = loadSystemPrompt();
        loadSkills(skillPaths);
        loadAgentTasks(agentPaths);
        loadTools(applicationEventPublisher, agentToolList);
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
        } else if (systemMessage.getText() != null) {
            addChatMemory(conversationId, systemMessage);
            log.info("System Message loaded successfully");
        }

        if (!skillResources.isEmpty()) {
            agentTools.addFirst(SkillsTool.builder().addSkillsResources(skillResources).build());
            log.info("Skills loaded successfully");
        }

        if (!AgentTasksResources.isEmpty()) {
            TaskTool.Builder taskToolBuilder = TaskTool.builder()
                    .taskRepository(new DefaultTaskRepository())
                    .subagentReferences(ClaudeSubagentReferences.fromResources(AgentTasksResources));
            ChatClient.Builder chatClientBuilder = ChatClient.create(chatModel).mutate()
                    .defaultAdvisors(ImageReaderToolResponseAdvisor.builder().responseConverter(this::decodeAndDescribeImage).build(),
                            MyLoggingAdvisor.builder().showAvailableTools(true).showSystemMessage(true).labelPrefix("[SUB-AGENT] ").build());
            ClaudeSubagentType.Builder claudeSubagentTypeBuilder = ClaudeSubagentType.builder().chatClientBuilder("default", chatClientBuilder);
            if (!skillResources.isEmpty()) {
                claudeSubagentTypeBuilder.skillsResources(skillResources);
            }
            taskToolBuilder.subagentTypes(claudeSubagentTypeBuilder.build());
            agentTools.addFirst(taskToolBuilder.build());
            log.info("Agents loaded successfully");
        }

        ChatOptions chatOptions = maxCompletionTokens == null ?
                chatModel.getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).build() :
                chatModel.getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).maxCompletionTokens(maxCompletionTokens).build();

        try {
            loadPromptFile(chatOptions, promptFileName);
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
                            processCall(chatOptions, List.of(UserMessage.builder().text(buffer.toString()).build()));
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

    private ChatResponse processCall(ChatOptions chatOptions, List<Message> messages) {
        addChatMemory(conversationId, messages);
        Prompt prompt = new Prompt(chatMemory.get(conversationId), chatOptions);
        ChatResponse chatResponse = chatModel.call(prompt);
        addChatMemory(conversationId, chatResponse.getResult().getOutput());
        if (chatResponse.getResult().getOutput().getMetadata().containsKey("reasoningContent")) {
            System.out.printf("%sReasoning Content:[%n%s]%s%n", ColorEnum.YELLOW_BOLD_BRIGHT, chatResponse.getResult().getOutput().getMetadata().get("reasoningContent"), ColorEnum.RESET);
        }
        System.out.printf("%s%s%s%n", ColorEnum.GREEN_BOLD_BRIGHT, chatResponse.getResult().getOutput().getText(), ColorEnum.RESET);
        System.out.printf("%s%s%s%n", ColorEnum.GREEN, jsonMapper.writeValueAsString(chatResponse.getMetadata().getRateLimit()), ColorEnum.RESET);
        System.out.printf("%s%s%s%n%n", ColorEnum.GREEN, chatResponse.getMetadata().getUsage(), ColorEnum.RESET);
        return processToolCall(chatOptions, chatResponse);
    }

    private ChatResponse processToolCall(ChatOptions chatOptions, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls()) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(this::toolCallToString);
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemory.get(conversationId), chatOptions), chatResponse);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory().getLast();
            toolResponseMessage.getResponses().forEach(this::toolResponseToString);
            if (toolResponseMessage.getResponses().stream().anyMatch(toolResponse -> toolResponse.name().equals(ImageReaderTool.name))) {
                ArrayList<Message> messageArrayList = new ArrayList<>();
                messageArrayList.add(toolResponseMessage);
                messageArrayList.add(decodeAndDescribeImage(toolResponseMessage));
                chatResponse = processCall(chatOptions, messageArrayList);
            } else {
                chatResponse = processCall(chatOptions, List.of(toolResponseMessage));
            }
        }
        return chatResponse;
    }

    private void toolCallToString(AssistantMessage.ToolCall toolCall) {
        System.out.println(ColorEnum.CYAN + String.format("TollCall[id=%s, type=%s, name=%s, arguments={%s}]", toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments().substring(0, Math.min(132, toolCall.arguments().length()))) + ColorEnum.RESET);
    }

    private void toolResponseToString(ToolResponseMessage.ToolResponse toolResponse) {
        System.out.println(ColorEnum.MAGENTA + String.format("ToolResponse[id=%s, name=%s, responseData=%s]", toolResponse.id(), toolResponse.name(), toolResponse.responseData().substring(0, Math.min(132, toolResponse.responseData().length()))) + ColorEnum.RESET);
    }

    private void loadPromptFile(ChatOptions chatOptions, String promptFileName) {
        try {
            if (promptFileName == null) return;
            File promptFile = new File(promptFileName);
            if (promptFile.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(promptFile)) {
                    log.info("Prompt file loaded successfully. {}", promptFile.getAbsolutePath());
                    processCall(chatOptions, List.of(UserMessage.builder().text(StreamUtils.copyToString(fileInputStream, StandardCharsets.UTF_8)).build()));
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
            systemPromptEnvMap.put("AGENT_MAX_COMPLETION_TOKEN", maxCompletionTokens == null ? "as defined" : maxCompletionTokens);

            PromptTemplate systemTemplate = new PromptTemplate(operatorSystemPrompt);
            return SystemMessage.builder().text(systemTemplate.render(systemPromptEnvMap)).build();
        }
        return null;
    }

    private void loadSkills(List<String> skillPaths) {
        if (skillPaths == null || skillPaths.isEmpty()) return;
        skillPaths.forEach(skillPath -> {
            if (skillPath.startsWith("/")) {
                FileSystemResource resource = new FileSystemResource(skillPath);
                if (Utils.containsFile(resource.getFile(), "SKILL.md")) {
                    skillResources.add(resource);
                }
            } else if (!skillPath.isEmpty()) {
                skillResources.add(resourceLoader.getResource(skillPath));
            }
        });
    }

    private void loadAgentTasks(List<String> agentPaths) {
        if (agentPaths == null || agentPaths.isEmpty()) return;
        agentPaths.forEach(agentPath -> {
            if (agentPath.startsWith("/")) AgentTasksResources.add(new FileSystemResource(agentPath));
            else if (!agentPath.isEmpty()) AgentTasksResources.add(resourceLoader.getResource(agentPath));
        });
    }

    private void loadTools(ApplicationEventPublisher applicationEventPublisher, String[] agentToolList) {
        ArrayList<ToolCallback[]> toolList = new ArrayList<>();
        for (String at : agentToolList) {
            switch (at) {
                case "ImageReaderTool" -> toolList.add(ToolCallbacks.from(ImageReaderTool.builder().build()));
                case "GrepTool" -> toolList.add(ToolCallbacks.from(GrepTool.builder().build()));
                case "GlobTool" -> toolList.add(ToolCallbacks.from(GlobTool.builder().build()));
                case "ShellTools" -> toolList.add(ToolCallbacks.from(ShellTools.builder().build()));
                case "FileSystemTools" -> toolList.add(ToolCallbacks.from(FileSystemTools.builder().build()));
                case "ListDirectoryTool" -> toolList.add(ToolCallbacks.from(ListDirectoryTool.builder().build()));
                case "SmartWebFetchTool" ->
                        toolList.add(ToolCallbacks.from(SmartWebFetchTool.builder(ChatClient.create(chatModel)).build()));
                case "AskUserQuestionTool" ->
                        toolList.add(ToolCallbacks.from(AskUserQuestionTool.builder().questionHandler(new CommandLineQuestionHandler()).build()));
                case "TodoWriteTool" ->
                        toolList.add(ToolCallbacks.from(TodoWriteTool.builder().todoEventHandler(event -> applicationEventPublisher.publishEvent(new TodoUpdateEvent(this, event.todos()))).build()));
            }
        }
        agentTools.addAll(Arrays.asList(toolList.stream().flatMap(Arrays::stream).toArray(ToolCallback[]::new)));
    }

    private void addChatMemory(String conversationId, Message message) {
        addChatMemory(conversationId, List.of(message));
    }

    private void addChatMemory(String conversationId, List<Message> message) {
        chatMemory.add(conversationId, message);
    }

    private Message decodeAndDescribeImage(ToolResponseMessage toolResponseMessage) {
        Message message = toolResponseMessage;
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
            if (toolResponse.name().equals(ImageReaderTool.name)) {
                UserMessage.Builder builder = UserMessage.builder().text(ImageReaderTool.name);
                FileSystemResource resource = new FileSystemResource(toolResponse.responseData().replace("\"", ""));
                try {
                    MimeType mimeType = MimeTypeUtils.parseMimeType(Files.probeContentType(resource.getFilePath()));
                    if (!mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_JPEG) && !mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_PNG)) {
                        builder.text("Can't load images other than PNG or JPEG").build();
                    }
                    builder.media(new Media(mimeType, resource));
                } catch (Exception e) {
                    builder.text(e.getMessage()).build();
                }
                message = builder.build();
            }
        }
        return message;
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
