package guru.kumo.operator.service;

import guru.kumo.operator.configuration.CustomMcpConfig;
import guru.kumo.operator.tool.ImageReaderTool;
import guru.kumo.operator.tool.TodoUpdateEvent;
import guru.kumo.operator.tool.TodoWriteTool;
import guru.kumo.operator.util.ColorEnum;
import guru.kumo.operator.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class OperatorService {
    private static final ArrayList<ToolCallback> agentTools = new ArrayList<>();
    private final ChatMemoryService chatMemoryService;
    private final JsonMapper jsonMapper;
    private final OpenAiChatModel chatModel;
    private final Integer maxCompletionTokens;
    private final CustomMcpConfig customMcpConfig;
    private final ToolCallingManager toolCallingManager;

    OperatorService(
            OpenAiChatModel openAiChatModel,
            CustomMcpConfig customMcpConfig,
            ChatMemoryService chatMemoryService,
            ApplicationEventPublisher applicationEventPublisher,
            @Value("${agent.tools}") String[] agentToolList,
            @Value("${agent.paths.agents}") List<String> agentPaths,
            @Value("${agent.paths.skills}") List<String> skillPaths,
            @Value("${agent.chat-model.max-completion-tokens}") Integer maxCompletionTokens) {
        this.chatModel = openAiChatModel;
        this.chatMemoryService = chatMemoryService;
        this.customMcpConfig = customMcpConfig;
        this.maxCompletionTokens = maxCompletionTokens;
        this.jsonMapper = JsonMapper.builder().build();
        this.toolCallingManager = ToolCallingManager.builder().build();
        loadTools(applicationEventPublisher, agentToolList, loadSkills(skillPaths), loadAgentTasks(agentPaths), customMcpConfig.customMcpToolCallbackProvider());
    }

    public void shutdown() {
        customMcpConfig.shutdown();
    }

    public ChatResponse processCall(String logPrefix, String conversationId, List<Message> messages) {
        chatMemoryService.addChatMemory(conversationId, messages);
        Prompt prompt = new Prompt(chatMemoryService.getChatMemory(conversationId), getChatOptions());
        ChatResponse chatResponse = chatModel.call(prompt);
        chatMemoryService.addChatMemory(conversationId, chatResponse.getResult().getOutput());
        if (chatResponse.getResult().getOutput().getMetadata().containsKey("reasoningContent")) {
            System.out.printf("%s%s REASONING:[%n%s]%s%n", ColorEnum.YELLOW_BOLD_BRIGHT, logPrefix, chatResponse.getResult().getOutput().getMetadata().get("reasoningContent"), ColorEnum.RESET);
        }
        System.out.printf("%s%s ASSISTANT:[%n%s%n]%s%n", ColorEnum.GREEN_BOLD_BRIGHT, logPrefix, chatResponse.getResult().getOutput().getText(), ColorEnum.RESET);
        System.out.printf("%s%s %s%s%n", ColorEnum.GREEN, logPrefix, jsonMapper.writeValueAsString(chatResponse.getMetadata().getRateLimit()), ColorEnum.RESET);
        System.out.printf("%s%s %s%s%n%n", ColorEnum.GREEN, logPrefix, chatResponse.getMetadata().getUsage(), ColorEnum.RESET);
        return processToolCall(logPrefix, conversationId, chatResponse);
    }

    private ChatResponse processToolCall(String logPrefix, String conversationId, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls()) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> toolCallToString(logPrefix, toolCall));
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemoryService.getChatMemory(conversationId), getChatOptions()), chatResponse);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory().getLast();
            toolResponseMessage.getResponses().forEach(toolCallResponse -> toolResponseToString(logPrefix, toolCallResponse));
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

    private void toolCallToString(String logPrefix, AssistantMessage.ToolCall toolCall) {
        System.out.println(ColorEnum.CYAN + String.format("%s TollCall[id=%s, type=%s, name=%s, arguments={%s}]", logPrefix, toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments().substring(0, Math.min(132, toolCall.arguments().length()))) + ColorEnum.RESET);
    }

    private void toolResponseToString(String logPrefix, ToolResponseMessage.ToolResponse toolResponse) {
        System.out.println(ColorEnum.MAGENTA + String.format("%s ToolResponse[id=%s, name=%s, responseData=%s]", logPrefix, toolResponse.id(), toolResponse.name(), toolResponse.responseData().substring(0, Math.min(132, toolResponse.responseData().length()))) + ColorEnum.RESET);
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

    private void loadTools(ApplicationEventPublisher applicationEventPublisher, String[] agentToolList,
                           List<Resource> skillResources, List<Resource> agentTasksResources, ToolCallbackProvider mcpTools) {
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
                case "ShellTools" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(ShellTools.builder().build()).build().getToolCallbacks()));
                    log.info("ShellTools loaded successfully");
                }
                case "FileSystemTools" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(FileSystemTools.builder().build()).build().getToolCallbacks()));
                    log.info("FileSystemTools loaded successfully");
                }
                case "ListDirectoryTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(ListDirectoryTool.builder().build()).build().getToolCallbacks()));
                    log.info("ListDirectoryTool loaded successfully");
                }
                case "SmartWebFetchTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(SmartWebFetchTool.builder(ChatClient.create(chatModel)).build()).build().getToolCallbacks()));
                    log.info("SmartWebFetchTool loaded successfully");
                }
                case "TodoWriteTool" -> {
                    agentTools.addAll(Arrays.asList(MethodToolCallbackProvider.builder()
                            .toolObjects(TodoWriteTool.builder().todoEventHandler(event -> applicationEventPublisher.publishEvent(new TodoUpdateEvent(this, event.todos()))).build()).build().getToolCallbacks()));
                    log.info("TodoWriteTool loaded successfully");
                }
            }
        }
        agentTools.addAll(Arrays.asList(mcpTools.getToolCallbacks()));
        log.info("Total of {} agent tool(s) loaded successfully.", agentTools.size());
    }

    private ChatOptions getChatOptions() {
        return maxCompletionTokens == null ?
                chatModel.getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).build() :
                chatModel.getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).maxCompletionTokens(maxCompletionTokens).build();
    }
}

