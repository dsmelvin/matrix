package guru.kumo.operator.service;

import discord4j.core.GatewayDiscordClient;
import guru.kumo.operator.channel.model.AgentResponse;
import guru.kumo.operator.configuration.AgentProfilesConfiguration;
import guru.kumo.operator.configuration.CustomMcpConfiguration;
import guru.kumo.operator.configuration.GoogleGenAiConfiguration;
import guru.kumo.operator.configuration.SkillProfilesConfiguration;
import guru.kumo.operator.model.AgentProfile;
import guru.kumo.operator.model.SkillProfile;
import guru.kumo.operator.tool.*;
import guru.kumo.operator.util.ColorEnum;
import guru.kumo.operator.util.EnvFileLoader;
import lombok.extern.slf4j.Slf4j;
import org.jline.terminal.Terminal;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("operator")
public class AgentOperator {
    private static final String GOOGLE_GENAI = "google-genai";
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
            Terminal terminal,
            ApplicationContext applicationContext,
            ChatMemoryService chatMemoryService,
            CustomMcpConfiguration customMcpConfiguration,
            @Value("${agent.tools}") String[] agentToolList,
            AgentProfilesConfiguration agentProfilesConfiguration,
            SkillProfilesConfiguration skillProfilesConfiguration,
            ObjectProvider<GatewayDiscordClient> gatewayDiscordClientProvider,
            @Value("${agent.channel.discord.token}") String discordToken) {
        // Only log in to Discord when a token is configured at runtime (the client bean itself is lazy).
        GatewayDiscordClient gatewayDiscordClient = StringUtils.hasText(discordToken) ? gatewayDiscordClientProvider.getIfAvailable() : null;
        ChatModel chatModel = selectChatModel(applicationContext);
        this.chatModel = chatModel;
        this.imageModel = selectImageModel(applicationContext);
        this.chatMemoryService = chatMemoryService;
        this.customMcpConfiguration = customMcpConfiguration;

        this.agentProfileMap = agentProfilesConfiguration.getAgentProfileMap();
        this.skillProfileMap = skillProfilesConfiguration.getSkillProfileMap();
        this.builtInCallbackToolList = loadTools(chatModel, gatewayDiscordClient, customMcpConfiguration.customMcpToolCallbackProvider(), this::publish);
        this.builtInCallbackToolList.forEach(toolCallback -> builtInCallbackToolMap.put(toolCallback.getToolDefinition().name(), toolCallback));
        this.allowedTools = Arrays.stream(agentToolList).filter(builtInCallbackToolMap::containsKey).map(builtInCallbackToolMap::get).collect(Collectors.toSet());

        this.toolCallingManager = OperatorToolCallingManager.builder().terminal(terminal).maxCallsPerTool(200).maxTotalToolCalls(1000).build();
        if (chatModel instanceof OpenAiChatModel) {
            this.toolCallingChatOptionsBuilder = ((OpenAiChatModel) chatModel).getOptions().mutate().timeout(Duration.ofMinutes(30)).parallelToolCalls(true);
        } else {
            this.toolCallingChatOptionsBuilder = ((ToolCallingChatOptions) chatModel.getOptions()).mutate();
        }
    }

    // The provider is chosen at runtime (AI_MODEL_CHAT / AI_MODEL_IMAGE from the env file or OS environment), because
    // @Conditional* is frozen at build time in a native image. See GoogleGenAiConfiguration.
    private static ChatModel selectChatModel(ApplicationContext context) {
        if (GOOGLE_GENAI.equals(EnvFileLoader.getenv("AI_MODEL_CHAT"))) {
            log.info("Chat model provider: {}", GOOGLE_GENAI);
            return context.getBean(GoogleGenAiConfiguration.CHAT_MODEL_BEAN_NAME, ChatModel.class);
        }
        log.info("Chat model provider: openai");
        ChatModel openAiChatModel = context.getBeanProvider(OpenAiChatModel.class).getIfAvailable();
        return openAiChatModel != null ? openAiChatModel : context.getBean(ChatModel.class);
    }

    private static ImageModel selectImageModel(ApplicationContext context) {
        if (GOOGLE_GENAI.equals(EnvFileLoader.getenv("AI_MODEL_IMAGE"))) {
            return context.getBean(GoogleGenAiConfiguration.IMAGE_MODEL_BEAN_NAME, ImageModel.class);
        }
        // Neither getIfUnique() nor stream() work here: the lazy Google image model is registered as an ImageModel
        // even though it is not an autowire candidate, and would get instantiated (NPE on the missing Google
        // credentials). So look up the names and skip it explicitly.
        String[] names = Arrays.stream(context.getBeanNamesForType(ImageModel.class))
                .filter(name -> !GoogleGenAiConfiguration.IMAGE_MODEL_BEAN_NAME.equals(name))
                .toArray(String[]::new);
        return names.length == 1 ? context.getBean(names[0], ImageModel.class) : null;
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
        String logPrefix = ColorEnum.getRandomColor() + "[" + taskCall.subagent_type() + "]" + ColorEnum.RESET;
        publish(new AgentResponse(logPrefix, taskCall, systemMessage, userMessage));
        ChatResponse chatResponse = processCall(toolCallingChatOptions, logPrefix, conversationId, List.of(systemMessage, userMessage));

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
        try {
            publish(new AgentResponse(logPrefix, toolCallingChatOptions));
            chatMemoryService.addChatMemory(conversationId, messages);
            Prompt prompt = new Prompt(chatMemoryService.getChatMemory(conversationId), toolCallingChatOptions);
            ChatResponse chatResponse = chatModel.call(prompt);
            chatMemoryService.addChatMemory(conversationId, chatResponse.getResult());
            publish(new AgentResponse(logPrefix, chatResponse));
            return chatResponse.hasToolCalls() ? processToolCall(toolCallingChatOptions, logPrefix, conversationId, chatResponse) : chatResponse;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(e.getMessage()).build())));
        }
    }

    private ChatResponse processToolCall(ToolCallingChatOptions toolCallingChatOptions, String logPrefix, String conversationId, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls() && chatResponse.getResult() != null) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> publish(new AgentResponse(logPrefix, toolCall)));
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemoryService.getChatMemory(conversationId), toolCallingChatOptions), chatResponse);
            chatResponse = processCall(toolCallingChatOptions, logPrefix, conversationId, processImageReaderTool(logPrefix, List.of(toolExecutionResult.conversationHistory().getLast())));
        }
        return chatResponse;
    }

    private List<Message> processImageReaderTool(String logPrefix, List<Message> conversationHistory) {
        ArrayList<Message> messageArrayList = new ArrayList<>();
        conversationHistory.forEach(conversationHistoryMessage -> {
            if (conversationHistoryMessage instanceof ToolResponseMessage toolResponseMessage) {
                // Spring AI 2.1 copies the responses into message parts when build() is called, so the
                // list must be fully populated before building the ToolResponseMessage.
                ArrayList<ToolResponseMessage.ToolResponse> toolResponseArrayList = new ArrayList<>();
                ArrayList<Message> imageMessageArrayList = new ArrayList<>();
                toolResponseMessage.getResponses().forEach(toolCallResponse -> {
                    if (toolCallResponse.name().equals(ImageReaderTool.TOOL_NAME)) {
                        String imagePathName = toolCallResponse.responseData().replace("\"", "");
                        ToolResponseMessage.ToolResponse newToolResponse;
                        try {
                            imageMessageArrayList.add(decodeAndDescribeImage(imagePathName));
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
                messageArrayList.add(ToolResponseMessage.builder().responses(toolResponseArrayList).build());
                messageArrayList.addAll(imageMessageArrayList);
            } else {
                messageArrayList.add(conversationHistoryMessage);
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
        List<Path> allowedDirectoryList = new ArrayList<>();
        String workingDirectory = System.getProperty("user.dir");
        allowedDirectoryList.add(Path.of("/tmp"));
        allowedDirectoryList.add(Path.of(System.getProperty("java.io.tmpdir")));
        allowedDirectoryList.add(Path.of(workingDirectory));

        if (!agentProfileMap.isEmpty()) {
            builtInCallbackToolList.add(TaskTool.builder().addAgentOperator(this)
                    .addAgentProfiles(agentProfileMap).addSkillProfiles(skillProfileMap).taskRepository(defaultTaskRepository).build());
            builtInCallbackToolList.add(TaskOutputTool.builder().taskRepository(defaultTaskRepository).build());
        }

        if (!skillProfileMap.isEmpty()) {
            builtInCallbackToolList.add(SkillsTool.builder().addSkillsResources(skillProfileMap).build());
        }
        skillProfileMap.forEach((x, y) -> {
            allowedDirectoryList.add(y.getBaseDirectory());
        });

        if (gatewayDiscordClient != null) {
            builtInCallbackToolList.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                    DiscordTool.builder().gatewayDiscordClient(gatewayDiscordClient).build()).build().getToolCallbacks()));
        }

        builtInCallbackToolList.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(
                ShellTools.builder().workingDirectory(workingDirectory).writableDirectories(allowedDirectoryList).build(),
                ImageReaderTool.builder().build(),
                SmartWebFetchTool.builder(ChatClient.create(chatModel)).build(),
                GrepTool.builder().workingDirectory(workingDirectory).build(),
                GlobTool.builder().workingDirectory(workingDirectory).build(),
                FileSystemTools.builder().allowedDirectories(allowedDirectoryList.toArray(new Path[0])).build(),
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

