package guru.kumo.operator.service;

import guru.kumo.operator.tool.ImageReaderTool;
import guru.kumo.operator.util.ColorEnum;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
@Profile("operator")
public class OperatorService {
    private final JsonMapper jsonMapper;
    private final ToolCallingManager toolCallingManager;
    private final Integer maxCompletionTokens;

    OperatorService(@Value("${agent.chat-model.max-completion-tokens}") Integer maxCompletionTokens) {
        this.jsonMapper = JsonMapper.builder().build();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.maxCompletionTokens = maxCompletionTokens;
    }

    private ChatOptions getChatOptions(ChatModel chatModel, List<ToolCallback> agentTools) {
        return maxCompletionTokens == null ?
                ((OpenAiChatModel) chatModel).getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).build() :
                ((OpenAiChatModel) chatModel).getOptions().mutate().toolCallbacks(agentTools).parallelToolCalls(true).maxCompletionTokens(maxCompletionTokens).build();
    }

    public ChatResponse processCall(String logPrefix, ChatModel chatModel, List<ToolCallback> agentTools, ChatMemory chatMemory, String conversationId, List<Message> messages) {
        chatMemory.add(conversationId, messages);
        Prompt prompt = new Prompt(chatMemory.get(conversationId), getChatOptions(chatModel, agentTools));
        ChatResponse chatResponse = chatModel.call(prompt);
        chatMemory.add(conversationId, chatResponse.getResult().getOutput());
        if (chatResponse.getResult().getOutput().getMetadata().containsKey("reasoningContent")) {
            System.out.printf("%s%s REASONING:[%n%s]%s%n", ColorEnum.YELLOW_BOLD_BRIGHT, logPrefix, chatResponse.getResult().getOutput().getMetadata().get("reasoningContent"), ColorEnum.RESET);
        }
        System.out.printf("%s%s ASSISTANT:[%n%s%n]%s%n", ColorEnum.GREEN_BOLD_BRIGHT, logPrefix, chatResponse.getResult().getOutput().getText(), ColorEnum.RESET);
        System.out.printf("%s%s %s%s%n", ColorEnum.GREEN, logPrefix, jsonMapper.writeValueAsString(chatResponse.getMetadata().getRateLimit()), ColorEnum.RESET);
        System.out.printf("%s%s %s%s%n%n", ColorEnum.GREEN, logPrefix, chatResponse.getMetadata().getUsage(), ColorEnum.RESET);
        return processToolCall(logPrefix, chatModel, agentTools, chatMemory, conversationId, chatResponse);
    }

    private ChatResponse processToolCall(String logPrefix, ChatModel chatModel, List<ToolCallback> agentTools, ChatMemory chatMemory, String conversationId, ChatResponse chatResponse) {
        while (chatResponse.hasToolCalls()) {
            chatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> toolCallToString(logPrefix, toolCall));
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(new Prompt(chatMemory.get(conversationId), getChatOptions(chatModel, agentTools)), chatResponse);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory().getLast();
            toolResponseMessage.getResponses().forEach(toolCallResponse -> toolResponseToString(logPrefix, toolCallResponse));
            if (toolResponseMessage.getResponses().stream().anyMatch(toolResponse -> toolResponse.name().equals(ImageReaderTool.name))) {
                ArrayList<Message> messageArrayList = new ArrayList<>();
                messageArrayList.add(toolResponseMessage);
                messageArrayList.add(decodeAndDescribeImage(toolResponseMessage));
                chatResponse = processCall(logPrefix, chatModel, agentTools, chatMemory, conversationId, messageArrayList);
            } else {
                chatResponse = processCall(logPrefix, chatModel, agentTools, chatMemory, conversationId, List.of(toolResponseMessage));
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
}

