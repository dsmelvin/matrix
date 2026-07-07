package guru.kumo.operator.command;

import guru.kumo.operator.advisor.MyLoggingAdvisor;
import guru.kumo.operator.tool.ImageReaderTool;
import guru.kumo.operator.tool.TodoUpdateEvent;
import guru.kumo.operator.tool.TodoWriteTool;
import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springaicommunity.agent.utils.AgentEnvironment;
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
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@Profile("operator")
public class OperatorShellCommand {
    public static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();

    private final ChatMemory chatMemory;
    private final OpenAiChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> tools = new ArrayList<>();
    private final String conversationId;
    private final Terminal terminal;
    private final ResourceLoader resourceLoader;
    private final Resource operatorSystemPrompt;
    private final String sessionMemoryPathName;

    private List<Resource> loadSkills(List<String> skillPaths) {
        if (skillPaths == null || skillPaths.isEmpty()) return List.of();
        ArrayList<Resource> resources = new ArrayList<>();
        skillPaths.forEach(skillPath -> {
            if (skillPath.startsWith("/")) resources.add(new FileSystemResource(skillPath));
            else if (!skillPath.isEmpty()) resources.add(resourceLoader.getResource(skillPath));
        });
        if (!resources.isEmpty() && resources.stream().anyMatch(Resource::exists)) {
            tools.add(SkillsTool.builder().addSkillsResources(resources).build());
            log.info("Skills loaded successfully");
        }
        return resources;
    }

    private void loadAgentsAndSkills(List<String> agentPaths, List<String> skillPaths) {
        List<Resource> skillResources = loadSkills(skillPaths);

        if (agentPaths == null || agentPaths.isEmpty()) return;
        ArrayList<Resource> resources = new ArrayList<>();
        agentPaths.forEach(agentPath -> {
            if (agentPath.startsWith("/")) resources.add(new FileSystemResource(agentPath));
            else if (!agentPath.isEmpty()) resources.add(resourceLoader.getResource(agentPath));
        });
        if (!resources.isEmpty() && resources.stream().anyMatch(Resource::exists)) {
            TaskTool.Builder taskToolBuilder = TaskTool.builder().subagentReferences(ClaudeSubagentReferences.fromResources(resources));
            ClaudeSubagentType.Builder claudeSubagentTypeBuilder = ClaudeSubagentType.builder()
                    .chatClientBuilder("default", ChatClient.create(chatModel).mutate().defaultTools(tools)
                            .defaultAdvisors(MyLoggingAdvisor.builder().showAvailableTools(true).labelPrefix("[SUB-AGENT] ").build())
                    );
            if (!skillResources.isEmpty() && skillResources.stream().anyMatch(Resource::exists)) {
                claudeSubagentTypeBuilder.skillsResources(skillResources);
            }
            taskToolBuilder.subagentTypes(claudeSubagentTypeBuilder.build());
            tools.add(taskToolBuilder.build());
            log.info("Agents loaded successfully");
        }
    }

    OperatorShellCommand(Terminal terminal,
                         ResourceLoader resourceLoader,
                         OpenAiChatModel openAiChatModel,
                         ApplicationEventPublisher applicationEventPublisher,
                         @Value("${agent.prompt.system}") String operatorSystemPrompt,
                         @Value("${agent.path.memory}") String sessionMemoryPathName,
                         @Value("${agent.paths.agents}") List<String> agentPaths,
                         @Value("${agent.paths.skills}") List<String> skillPaths,
                         @Value("${agent.message-window-chat-memory.max-messages}") int maxMessages) {
        this.terminal = terminal;
        this.resourceLoader = resourceLoader;
        this.chatModel = openAiChatModel;
        this.conversationId = UUID.randomUUID().toString();

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

        loadAgentsAndSkills(agentPaths, skillPaths);

        tools.addAll(Arrays.stream(ToolCallbacks.from(
                ImageReaderTool.builder().build(),
                GrepTool.builder().build(),
                GlobTool.builder().build(),
                ShellTools.builder().build(),
                FileSystemTools.builder().build(),
                ListDirectoryTool.builder().build(),
                SmartWebFetchTool.builder(ChatClient.create(chatModel)).build(),
                AskUserQuestionTool.builder().questionHandler(new CommandLineQuestionHandler()).build(),
                TodoWriteTool.builder().todoEventHandler(event -> applicationEventPublisher.publishEvent(new TodoUpdateEvent(this, event.todos()))).build()
        )).toList());
    }

    private void addChatMemory(String conversationId, Message message) {
        addChatMemory(conversationId, List.of(message));
    }

    private void addChatMemory(String conversationId, List<Message> message) {
        chatMemory.add(conversationId, message);
    }

    @Command(name = {"operator"})
    public void run(CommandContext commandContext,
                    @Option(longName = "save-session-memory", shortName = 's', required = false, description = "flag of storing session memory into a file")
                    boolean saveSessionMemory,
                    @Option(longName = "session-memory-file", shortName = 'm', required = false, description = "The history of chat memory file")
                    String savedSessionMemoryFileName,
                    @Option(longName = "prompt-file", shortName = 'p', required = false, description = "To preload a prompt file")
                    String promptFileName) {
        terminal.handle(Terminal.Signal.INT, signal -> {
            saveSessionMemoryFile(saveSessionMemory);
        });
        ChatOptions chatOptions = chatModel.getOptions().mutate().toolCallbacks(tools).build();

        if (savedSessionMemoryFileName != null) {
            loadSavedSessionMemoryFile(savedSessionMemoryFileName);
        } else {
            loadSystemPrompt();
        }
        try {
            loadPromptFile(chatOptions, promptFileName);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.error("Failed to process chat message", e);
            } else {
                log.error("Failed to process chat message: {}", e.getMessage());
            }
        }
        try {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            StringBuilder buffer = new StringBuilder();
            boolean collecting = false;
            while (true) {
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
        System.out.printf("%s%s%s%n", ColorEnum.GREEN_BOLD_BRIGHT, chatResponse.getResult().getOutput().getText(), ColorEnum.RESET);
        if (chatResponse.getResult().getOutput().getMetadata().containsKey("reasoningContent")) {
            System.out.printf("%sReasoning Content:[%n%s]%s%n", ColorEnum.YELLOW_BOLD_BRIGHT, chatResponse.getResult().getOutput().getMetadata().get("reasoningContent"), ColorEnum.RESET);
        }
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
                toolResponseMessage.getResponses().forEach(toolResponse -> {
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
                        messageArrayList.add(builder.build());
                    }
                });
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

    private void loadSystemPrompt() {
        if (operatorSystemPrompt != null && operatorSystemPrompt.exists() && operatorSystemPrompt.isFile()) {
            PromptTemplate systemTemplate = new PromptTemplate(operatorSystemPrompt);
            String systemText = systemTemplate.render(Map.of(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info()));
            SystemMessage systemMessage = SystemMessage.builder().text(systemText).build();
            addChatMemory(conversationId, systemMessage);
            log.info("System Message loaded successfully");
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
