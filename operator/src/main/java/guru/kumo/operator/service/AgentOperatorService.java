package guru.kumo.operator.service;

import discord4j.core.GatewayDiscordClient;
import guru.kumo.operator.channel.model.AgentResponse;
import guru.kumo.operator.configuration.CustomMcpConfig;
import guru.kumo.operator.tool.DiscordTool;
import guru.kumo.operator.tool.ImageReaderTool;
import guru.kumo.operator.tool.TodoWriteTool;
import guru.kumo.operator.tool.task.claude.ClaudeSubagentType;
import guru.kumo.operator.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class AgentOperatorService {
    private static final ArrayList<ToolCallback> agentTools = new ArrayList<>();
    private static final Sinks.Many<AgentResponse> sink = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

    private final ChatModel chatModel;
    private final ImageModel imageModel;
    private final CustomMcpConfig customMcpConfig;
    private final ChatMemoryService chatMemoryService;
    private final ToolCallingManager toolCallingManager;
    private final GatewayDiscordClient gatewayDiscordClient;

    AgentOperatorService(
            ChatModel chatModel,
            CustomMcpConfig customMcpConfig,
            ChatMemoryService chatMemoryService,
            @Value("${agent.tools}") String[] agentToolList,
            @Value("${agent.paths.agents}") List<String> agentPaths,
            @Value("${agent.paths.skills}") List<String> skillPaths,
            @Autowired(required = false) ImageModel imageModel,
            @Autowired(required = false) GatewayDiscordClient gatewayDiscordClient) {
        this.chatModel = chatModel;
        this.imageModel = imageModel;
        this.chatMemoryService = chatMemoryService;
        this.customMcpConfig = customMcpConfig;
        this.gatewayDiscordClient = gatewayDiscordClient;
        this.toolCallingManager = ToolCallingManager.builder().build();
        loadTools(agentToolList, loadSkills(skillPaths), loadAgentTasks(agentPaths), customMcpConfig.customMcpToolCallbackProvider());
    }

    public Flux<AgentResponse> subscribe() {
        return sink.asFlux();
    }

    public void complete() {
        sink.tryEmitComplete();
    }

    public void shutdown() {
        complete();
        customMcpConfig.shutdown();
    }

    public ImageResponse processImageModelRequest(String prompt) {
        if (imageModel != null) {
            return imageModel.call(new ImagePrompt(prompt));
        }
        return null;
    }

    public void processConsoleInitMessage(String conversationId, ArrayList<Message> messageList) {
        publish(new AgentResponse(messageList));
        if (messageList.stream().anyMatch(message -> message.getMessageType() == MessageType.USER)) {
            processCall("[INIT]", conversationId, messageList);
        } else {
            chatMemoryService.addChatMemory(conversationId, messageList);
        }
    }

    public String processSubAgentRequest(String conversationId, TaskCall taskCall, SystemMessage systemMessage, UserMessage userMessage) {
        publish(new AgentResponse(taskCall, systemMessage, userMessage));
        return processCall("[" + taskCall.subagent_type() + "]", conversationId, List.of(systemMessage, userMessage)).getResult().getOutput().getText();
    }

    public void processConsoleRequest(String conversationId, List<Message> messageList) {
        publish(new AgentResponse(AgentResponse.Type.CONSOLE, messageList));
        processCall("[CONSOLE]", conversationId, messageList);
    }

    public String processDiscordRequest(String conversationId, List<Message> messageList) {
        publish(new AgentResponse(AgentResponse.Type.DISCORD, messageList));
        ChatResponse chatResponse = processCall("[DISCORD]", conversationId, messageList);
        if (StringUtils.hasLength(chatResponse.getResult().getOutput().getText().trim())) {
            return chatResponse.getResult().getOutput().getText();
        } else {
            return null;
        }
    }

    public String processTelegramRequest(String conversationId, List<Message> messageList) {
        publish(new AgentResponse(AgentResponse.Type.TELEGRAM, messageList));
        return processCall("[TELEGRAM]", conversationId, messageList).getResult().getOutput().getText();
    }

    private ChatResponse processCall(String logPrefix, String conversationId, List<Message> messages) {
        chatMemoryService.addChatMemory(conversationId, messages);
        Prompt prompt = new Prompt(chatMemoryService.getChatMemory(conversationId), getChatOptions());
        ChatResponse chatResponse = chatModel.call(prompt);
        chatMemoryService.addChatMemory(conversationId, chatResponse.getResult().getOutput());
        publish(new AgentResponse(logPrefix, chatResponse));
        return processToolCall(logPrefix, conversationId, chatResponse);
    }

    private ChatResponse processToolCall(String logPrefix, String conversationId, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls()) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> publish(new AgentResponse(logPrefix, toolCall)));
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemoryService.getChatMemory(conversationId), getChatOptions()), chatResponse);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory().getLast();
            toolResponseMessage.getResponses().forEach(toolCallResponse -> publish(new AgentResponse(logPrefix, toolCallResponse)));
            if (toolResponseMessage.getResponses().stream().anyMatch(toolResponse -> toolResponse.name().equals(ImageReaderTool.name))) {
                ArrayList<Message> messageArrayList = new ArrayList<>();
                messageArrayList.add(toolResponseMessage);
                messageArrayList.add(decodeAndDescribeImage(toolResponseMessage));
                chatResponse = processCall(logPrefix, conversationId, messageArrayList);
            } else {
                chatResponse = processCall(logPrefix, conversationId, List.of(toolResponseMessage));
            }
        }
        return chatResponse;
    }

    private void publish(AgentResponse agentResponse) {
        Sinks.EmitResult result = sink.tryEmitNext(agentResponse);

        if (result.isFailure()) {
            // Handle failure explicitly instead of silently dropping
            switch (result) {
                case FAIL_OVERFLOW -> System.err.println("Buffer overflow, dropping: " + agentResponse);
                case FAIL_NON_SERIALIZED -> {
                    // Not thread-safe by default; retry with emit() + a retry strategy
                    sink.emitNext(agentResponse, Sinks.EmitFailureHandler.busyLooping(java.time.Duration.ofMillis(100)));
                }
                default -> System.err.println("Emit failed: " + result + " for " + agentResponse);
            }
        }
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

    private List<Resource> loadSkills(List<String> skillPaths) {
        List<Resource> skillResources = new ArrayList<>();
        if (skillPaths == null || skillPaths.isEmpty()) return skillResources;
        skillPaths.forEach(skillPath -> {
            Path filePath = Path.of("").toAbsolutePath().resolve(skillPath);
            if (!filePath.toFile().isDirectory() && System.getenv("PWD") != null && !skillPath.startsWith("/")) {
                filePath = Path.of(System.getenv("PWD")).toAbsolutePath().resolve(skillPath);
            }
            FileSystemResource resource = new FileSystemResource(filePath);
            if (Utils.containsFile(resource.getFile(), "SKILL.md")) {
                log.info("Loading SKILL.md from {}", resource);
                skillResources.add(resource);
            } else {
                log.error("Failed to load SKILL.md from {}", resource);
            }
        });
        return skillResources;
    }

    private List<Resource> loadAgentTasks(List<String> agentPaths) {
        List<Resource> agentTasksResources = new ArrayList<>();
        if (agentPaths == null || agentPaths.isEmpty()) return agentTasksResources;
        agentPaths.forEach(agentPath -> {
            Path filePath = Path.of("").toAbsolutePath().resolve(agentPath);
            if (!filePath.toFile().isDirectory() && System.getenv("PWD") != null && !agentPath.startsWith("/")) {
                filePath = Path.of(System.getenv("PWD")).toAbsolutePath().resolve(agentPath);
            }
            FileSystemResource resource = new FileSystemResource(filePath);
            if (resource.exists() && resource.isFile()) {
                agentTasksResources.add(resource);
                log.info("Loading agentTasks from {}", resource);
            } else {
                log.error("Failed to load agentTasks from {}", resource);
            }
        });
        return agentTasksResources;
    }

    private void loadTools(String[] agentToolList, List<Resource> skillResources, List<Resource> agentTasksResources, ToolCallbackProvider mcpTools) {
        for (String at : agentToolList) {
            switch (at) {
                case "TaskTool" -> {
                    if (!agentTasksResources.isEmpty()) {
                        agentTools.add(TaskTool.builder().taskRepository(new DefaultTaskRepository())
                                .subagentReferences(ClaudeSubagentReferences.fromResources(agentTasksResources))
                                .subagentTypes(ClaudeSubagentType.builder().skillResources(skillResources).operatorService(this).build()).build());
                        log.info("TaskTool loaded successfully");
                    }
                }
                case "SkillTool" -> {
                    if (!skillResources.isEmpty()) {
                        agentTools.add(SkillsTool.builder().addSkillsResources(skillResources).build());
                        log.info("SkillTool loaded successfully");
                    }
                }
                case "DiscordTool" -> {
                    if (gatewayDiscordClient != null) {
                        agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(DiscordTool.builder().gatewayDiscordClient(gatewayDiscordClient).build()).build().getToolCallbacks()));
                        log.info("DiscordTool loaded successfully");
                    }
                }
                case "TodoWriteTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder()
                            .toolObjects(TodoWriteTool.builder().todoEventHandler(todos -> publish(new AgentResponse(todos))).build())
                            .build().getToolCallbacks()));
                    log.info("TodoWriteTool loaded successfully");
                }
                case "ImageReaderTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(ImageReaderTool.builder().build()).build().getToolCallbacks()));
                    log.info("ImageReaderTool loaded successfully");
                }
                case "GrepTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(GrepTool.builder().build()).build().getToolCallbacks()));
                    log.info("GrepTool loaded successfully");
                }
                case "GlobTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(GlobTool.builder().build()).build().getToolCallbacks()));
                    log.info("GlobTool loaded successfully");
                }
                case "FileSystemTools" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(FileSystemTools.builder().build()).build().getToolCallbacks()));
                    log.info("FileSystemTools loaded successfully");
                }
                case "SmartWebFetchTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(SmartWebFetchTool.builder(ChatClient.create(chatModel)).build()).build().getToolCallbacks()));
                    log.info("SmartWebFetchTool loaded successfully");
                }
                case "ShellTools" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(ShellTools.builder().build()).build().getToolCallbacks()));
                    log.info("ShellTools loaded successfully");
                }
            }
        }
        agentTools.addAll(Arrays.asList(mcpTools.getToolCallbacks()));
        log.info("Total of {} agent tool(s) loaded successfully.", agentTools.size());
    }

    private ChatOptions getChatOptions() {
        if (chatModel instanceof OpenAiChatModel) {
            return ((OpenAiChatModel) chatModel).getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).build();
        } else {
            return ((ToolCallingChatOptions) chatModel.getOptions()).mutate().toolCallbacks(agentTools).build();
        }
    }
}

