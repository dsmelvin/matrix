package guru.kumo.operator.service;

import discord4j.core.GatewayDiscordClient;
import guru.kumo.operator.channel.model.AgentResponse;
import guru.kumo.operator.configuration.CustomMcpConfig;
import guru.kumo.operator.tool.*;
import guru.kumo.operator.tool.agent.task.claude.ClaudeSubagentReferencesUtil;
import guru.kumo.operator.tool.agent.task.claude.ClaudeSubagentTypeUtil;
import guru.kumo.operator.tool.agent.task.model.TaskCall;
import guru.kumo.operator.tool.agent.task.repository.DefaultTaskRepository;
import guru.kumo.operator.util.Utils;
import lombok.extern.slf4j.Slf4j;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class AgentOperatorService {
    private static final DefaultTaskRepository defaultTaskRepository = new DefaultTaskRepository();
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
        this.toolCallingManager = OperatorToolCallingManager.builder().unlimitedTotalToolCalls().unlimitedCallsPerTool().build();
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
        defaultTaskRepository.shutdown();
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
        return chatResponse.hasToolCalls() ? processToolCall(logPrefix, conversationId, chatResponse) : chatResponse;
    }

    private ChatResponse processToolCall(String logPrefix, String conversationId, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls()) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> publish(new AgentResponse(logPrefix, toolCall)));
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemoryService.getChatMemory(conversationId), getChatOptions()), chatResponse);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory().getLast();
            ArrayList<Message> messageArrayList = new ArrayList<>();
            ArrayList<ToolResponseMessage.ToolResponse> toolResponseArrayList = new ArrayList<>();
            ToolResponseMessage newToolResponseMessage = ToolResponseMessage.builder().responses(toolResponseArrayList).build();
            messageArrayList.add(newToolResponseMessage);
            toolResponseMessage.getResponses().forEach(toolCallResponse -> {
                if (toolCallResponse.name().equals(ImageReaderTool.name)) {
                    String imagePathName = toolCallResponse.responseData().replace("\"", "");
                    ToolResponseMessage.ToolResponse newToolResponse;
                    try {
                        messageArrayList.add(decodeAndDescribeImage(imagePathName));
                        newToolResponse = new ToolResponseMessage.ToolResponse(toolCallResponse.id(), toolCallResponse.name(),
                                "{\"result\": \"Success to load " + imagePathName + "\"}");
                    } catch (Exception e) {
                        newToolResponse = new ToolResponseMessage.ToolResponse(toolCallResponse.id(), toolCallResponse.name(),
                                "{\"result\": \"Failed to load " + imagePathName + "\"}");
                    }
                    toolResponseArrayList.add(newToolResponse);
                    publish(new AgentResponse(logPrefix, newToolResponse));
                } else {
                    toolResponseArrayList.add(toolCallResponse);
                    publish(new AgentResponse(logPrefix, toolCallResponse));
                }
            });
            chatResponse = processCall(logPrefix, conversationId, messageArrayList);
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

    private Message decodeAndDescribeImage(String imagePathName) {
        UserMessage.Builder builder = UserMessage.builder();
        FileSystemResource resource = new FileSystemResource(imagePathName);
        try {
            MimeType mimeType = MimeTypeUtils.parseMimeType(Files.probeContentType(resource.getFilePath()));
            if (!mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_JPEG) && !mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_PNG)) {
                throw new RuntimeException("Can't load images other than PNG or JPEG");
            }
            builder.media(new Media(mimeType, resource));
            return builder.text("Here is the image content of " + imagePathName + " which is loaded by " + ImageReaderTool.name).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Resource> loadSkills(List<String> skillPaths) {
        List<Resource> skillResources = new ArrayList<>();
        if (skillPaths == null || skillPaths.isEmpty()) return skillResources;
        skillPaths.forEach(skillPath -> {
            FileSystemResource resource = new FileSystemResource(Utils.getAbsolutePath(skillPath));
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
            FileSystemResource resource = new FileSystemResource(Utils.getAbsolutePath(agentPath));
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
                        agentTools.add(TaskTool.builder().taskRepository(defaultTaskRepository)
                                .subagentReferences(ClaudeSubagentReferencesUtil.fromResources(agentTasksResources))
                                .subagentTypes(ClaudeSubagentTypeUtil.builder().skillResources(skillResources).operatorService(this).build()).build());
                        agentTools.add(TaskOutputTool.builder().taskRepository(defaultTaskRepository).build());
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
                        agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                                DiscordTool.builder().gatewayDiscordClient(gatewayDiscordClient).build()).build().getToolCallbacks()));
                        log.info("DiscordTool loaded successfully");
                    }
                }
                case "TodoWriteTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                                    TodoWriteTool.builder().todoEventHandler(todos -> publish(new AgentResponse(todos))).build())
                            .build().getToolCallbacks()));
                    log.info("TodoWriteTool loaded successfully");
                }
                case "ImageReaderTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                            ImageReaderTool.builder().build()).build().getToolCallbacks()));
                    log.info("ImageReaderTool loaded successfully");
                }
                case "GrepTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                            GrepTool.builder().workingDirectory(System.getProperty("user.dir")).build()).build().getToolCallbacks()));
                    log.info("GrepTool loaded successfully");
                }
                case "GlobTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                            GlobTool.builder().workingDirectory(System.getProperty("user.dir")).build()).build().getToolCallbacks()));
                    log.info("GlobTool loaded successfully");
                }
                case "FileSystemTools" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                            FileSystemTools.builder().allowedDirectory(System.getProperty("user.dir")).build()).build().getToolCallbacks()));
                    log.info("FileSystemTools loaded successfully");
                }
                case "SmartWebFetchTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                            SmartWebFetchTool.builder(ChatClient.create(chatModel)).build()).build().getToolCallbacks()));
                    log.info("SmartWebFetchTool loaded successfully");
                }
                case "ShellTools" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                            ShellTools.builder().build()).build().getToolCallbacks()));
                    log.info("ShellTools loaded successfully");
                }
            }
        }
        agentTools.addAll(Arrays.asList(mcpTools.getToolCallbacks()));
        log.info("Total of {} agent tool(s) loaded successfully.", agentTools.size());
    }

    private ChatOptions getChatOptions() {
        if (chatModel instanceof OpenAiChatModel) {
            return ((OpenAiChatModel) chatModel).getOptions().mutate().timeout(Duration.ofMinutes(30)).toolCallbacks(agentTools).parallelToolCalls(true).build();
        } else {
            return ((ToolCallingChatOptions) chatModel.getOptions()).mutate().toolCallbacks(agentTools).build();
        }
    }
}

