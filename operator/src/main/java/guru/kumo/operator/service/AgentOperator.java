package guru.kumo.operator.service;

import discord4j.core.GatewayDiscordClient;
import guru.kumo.operator.channel.model.AgentResponse;
import guru.kumo.operator.configuration.AgentProfilesConfiguration;
import guru.kumo.operator.configuration.CustomMcpConfiguration;
import guru.kumo.operator.configuration.SkillProfilesConfiguration;
import guru.kumo.operator.model.AgentProfile;
import guru.kumo.operator.model.SkillProfile;
import guru.kumo.operator.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("operator")
public class AgentOperator {
    private static final DefaultTaskRepository defaultTaskRepository = new DefaultTaskRepository();
    private static final Sinks.Many<AgentResponse> sink = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

    private final ChatModel chatModel;
    private final ImageModel imageModel;
    private final CustomMcpConfiguration customMcpConfiguration;
    private final ChatMemoryService chatMemoryService;
    private final ToolCallingManager toolCallingManager;
    private final ToolCallingChatOptions.Builder<? extends ToolCallingChatOptions.Builder<?>> toolCallingChatOptionsBuilder;
    private final Set<ToolCallback> allowedTools;
    private final List<ToolCallback> builtInCallbackToolList;
    private final Map<String, ToolCallback> builtInCallbackToolMap = new HashMap<>();
    private final Map<String, AgentProfile> agentProfileMap;
    private final Map<String, SkillProfile> skillProfileMap;

    AgentOperator(
            ChatModel chatModel,
            ChatMemoryService chatMemoryService,
            CustomMcpConfiguration customMcpConfiguration,
            @Value("${agent.tools}") String[] agentToolList,
            AgentProfilesConfiguration agentProfilesConfiguration,
            SkillProfilesConfiguration skillProfilesConfiguration,
            @Autowired(required = false) ImageModel imageModel,
            @Autowired(required = false) GatewayDiscordClient gatewayDiscordClient) {
        this.chatModel = chatModel;
        this.imageModel = imageModel;
        this.chatMemoryService = chatMemoryService;
        this.customMcpConfiguration = customMcpConfiguration;

        this.agentProfileMap = agentProfilesConfiguration.getAgentProfileMap();
        this.skillProfileMap = skillProfilesConfiguration.getSkillProfileMap();
        this.builtInCallbackToolList = loadTools(chatModel, gatewayDiscordClient, customMcpConfiguration.customMcpToolCallbackProvider(), this::publish);
        this.builtInCallbackToolList.forEach(toolCallback -> builtInCallbackToolMap.put(toolCallback.getToolDefinition().name(), toolCallback));
        this.allowedTools = Arrays.stream(agentToolList).filter(builtInCallbackToolMap::containsKey).map(builtInCallbackToolMap::get).collect(Collectors.toSet());

        this.toolCallingManager = OperatorToolCallingManager.builder().unlimitedTotalToolCalls().unlimitedCallsPerTool().build();
        if (chatModel instanceof OpenAiChatModel) {
            this.toolCallingChatOptionsBuilder = ((OpenAiChatModel) chatModel).getOptions().mutate().timeout(Duration.ofMinutes(30)).parallelToolCalls(true);
        } else {
            this.toolCallingChatOptionsBuilder = ((ToolCallingChatOptions) chatModel.getOptions()).mutate();
        }
    }

    public ImageResponse processImageModelRequest(String prompt) {
        if (imageModel != null) {
            return imageModel.call(new ImagePrompt(prompt));
        }
        return null;
    }

    public String processSubAgentRequest(String conversationId, TaskCall taskCall, SystemMessage systemMessage, UserMessage userMessage) {
        if (!agentProfileMap.containsKey(taskCall.subagent_type())) {
            return "No subagent found with name: " + taskCall.subagent_type();
        }
        AgentProfile agentProfile = agentProfileMap.get(taskCall.subagent_type());
        List<ToolCallback> agentToolCallback = agentProfile.getTools().isEmpty() ? builtInCallbackToolList :
                agentProfile.getTools().stream().filter(builtInCallbackToolMap::containsKey).map(builtInCallbackToolMap::get).toList();
        List<ToolCallback> toolCallbacks = agentToolCallback.stream()
                .filter(x -> !agentProfile.getDisallowedTools().contains(x.getToolDefinition().name()))
                .collect(Collectors.toList());
        ToolCallingChatOptions toolCallingChatOptions = toolCallingChatOptionsBuilder.toolCallbacks(toolCallbacks).build();
        publish(new AgentResponse(taskCall, systemMessage, userMessage));
        ChatResponse chatResponse = processCall(toolCallingChatOptions, "[" + taskCall.subagent_type() + "]", conversationId, List.of(systemMessage, userMessage));
        if (chatResponse.getResult() != null) {
            return chatResponse.getResult().getOutput().getText();
        } else {
            return "No Response.";
        }
    }

    public ChatResponse processCall(String logPrefix, String conversationId, List<Message> messages) {
        List<ToolCallback> toolCallbacks = builtInCallbackToolList.stream().filter(allowedTools::contains).collect(Collectors.toList());
        ToolCallingChatOptions toolCallingChatOptions = toolCallingChatOptionsBuilder.toolCallbacks(toolCallbacks).build();
        return processCall(toolCallingChatOptions, logPrefix, conversationId, messages);
    }

    private ChatResponse processCall(ToolCallingChatOptions toolCallingChatOptions, String logPrefix, String conversationId, List<Message> messages) {
        chatMemoryService.addChatMemory(conversationId, messages);
        Prompt prompt = new Prompt(chatMemoryService.getChatMemory(conversationId), toolCallingChatOptions);
        ChatResponse chatResponse = chatModel.call(prompt);
        chatMemoryService.addChatMemory(conversationId, chatResponse.getResult());
        publish(new AgentResponse(logPrefix, chatResponse));
        return chatResponse.hasToolCalls() ? processToolCall(toolCallingChatOptions, logPrefix, conversationId, chatResponse) : chatResponse;
    }

    private ChatResponse processToolCall(ToolCallingChatOptions toolCallingChatOptions, String logPrefix, String conversationId, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls() && chatResponse.getResult() != null) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> publish(new AgentResponse(logPrefix, toolCall)));
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemoryService.getChatMemory(conversationId), toolCallingChatOptions), chatResponse);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory().getLast();
            List<Message> messageArrayList = processImageReaderTool(logPrefix, toolResponseMessage);
            chatResponse = processCall(toolCallingChatOptions, logPrefix, conversationId, messageArrayList);
        }
        return chatResponse;
    }

    private List<Message> processImageReaderTool(String logPrefix, ToolResponseMessage toolResponseMessage) {
        ArrayList<Message> messageArrayList = new ArrayList<>();
        ArrayList<ToolResponseMessage.ToolResponse> toolResponseArrayList = new ArrayList<>();
        ToolResponseMessage newToolResponseMessage = ToolResponseMessage.builder().responses(toolResponseArrayList).build();
        messageArrayList.add(newToolResponseMessage);
        toolResponseMessage.getResponses().forEach(toolCallResponse -> {
            if (toolCallResponse.name().equals(ImageReaderTool.TOOL_NAME)) {
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
        return messageArrayList;
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
            return builder.text("Here is the image content of " + imagePathName + " which is loaded by " + ImageReaderTool.TOOL_NAME).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<ToolCallback> loadTools(ChatModel chatModel, GatewayDiscordClient gatewayDiscordClient, ToolCallbackProvider mcpTools, Consumer<AgentResponse> consumer) {
        List<ToolCallback> builtInCallbackToolList = new ArrayList<>();

        if (!agentProfileMap.isEmpty()) {
            builtInCallbackToolList.add(TaskTool.builder().addAgentOperator(this)
                    .addAgentProfiles(agentProfileMap).addSkillProfiles(skillProfileMap).taskRepository(defaultTaskRepository).build());
            builtInCallbackToolList.add(TaskOutputTool.builder().taskRepository(defaultTaskRepository).build());
        }

        if (!skillProfileMap.isEmpty()) {
            builtInCallbackToolList.add(SkillsTool.builder().addSkillsResources(skillProfileMap).build());
        }

        if (gatewayDiscordClient != null) {
            builtInCallbackToolList.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                    DiscordTool.builder().gatewayDiscordClient(gatewayDiscordClient).build()).build().getToolCallbacks()));
        }

        String workingDirectory = System.getProperty("user.dir");
        builtInCallbackToolList.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                ShellTools.builder().build(),
                ImageReaderTool.builder().build(),
                SmartWebFetchTool.builder(ChatClient.create(chatModel)).build(),
                GrepTool.builder().workingDirectory(workingDirectory).build(),
                GlobTool.builder().workingDirectory(workingDirectory).build(),
                FileSystemTools.builder().allowedDirectory(workingDirectory).build(),
                TodoWriteTool.builder().todoEventHandler(todos -> consumer.accept(new AgentResponse(todos))).build()
        ).build().getToolCallbacks()));

        builtInCallbackToolList.addAll(Arrays.asList(mcpTools.getToolCallbacks()));
        log.info("Available Tools ({}): {}", builtInCallbackToolList.size(), builtInCallbackToolList.stream().map(toolCallback -> toolCallback.getToolDefinition().name()).sorted().collect(Collectors.joining(",")));
        return builtInCallbackToolList;
    }

    public void shutdown() {
        sink.tryEmitComplete();
        defaultTaskRepository.shutdown();
        customMcpConfiguration.shutdown();
    }

    public Flux<AgentResponse> subscribe() {
        return sink.asFlux();
    }

    public void publish(AgentResponse agentResponse) {
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
}

