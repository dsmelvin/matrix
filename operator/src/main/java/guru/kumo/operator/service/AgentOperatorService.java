package guru.kumo.operator.service;

import guru.kumo.operator.channel.model.AgentResponse;
import guru.kumo.operator.model.ChatCompletionRequest;
import guru.kumo.operator.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.image.ImageResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class AgentOperatorService {
    private static final JsonMapper jsonMapper = new JsonMapper();
    private final AgentOperator agentOperator;
    private final ChatMemoryService chatMemoryService;

    AgentOperatorService(AgentOperator agentOperator, ChatMemoryService chatMemoryService) {
        this.agentOperator = agentOperator;
        this.chatMemoryService = chatMemoryService;
    }

    public Flux<AgentResponse> subscribe() {
        return agentOperator.subscribe();
    }

    public void publishInferencePayload(ChatCompletionRequest chatCompletionRequest) {
        if (log.isDebugEnabled()) {
            log.debug("--> REQUEST BODY: {}", jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chatCompletionRequest).replace("\\\"", "\"").replace("\\t", "\t").replace("\\n", "\n"));
        }
    }

    public void publishInferencePayload(ChatCompletionResponse chatCompletionResponse) {
        if (log.isDebugEnabled()) {
            log.info("--> RESPONSE BODY: {}", jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chatCompletionResponse).replace("\\\"", "\"").replace("\\t", "\t").replace("\\n", "\n"));
        }
    }

    public ImageResponse processImageModelRequest(String prompt) {
        return agentOperator.processImageModelRequest(prompt);
    }

    public void processConsoleInitMessage(String conversationId, List<Message> messageList) {
        agentOperator.publish(new AgentResponse(messageList));
        if (messageList.stream().anyMatch(message -> message.getMessageType() == MessageType.USER)) {
            agentOperator.processCall("[INIT]", conversationId, messageList);
        } else {
            chatMemoryService.addChatMemory(conversationId, messageList);
        }
    }

    public void processConsoleRequest(String conversationId, List<Message> messageList) {
        agentOperator.publish(new AgentResponse(AgentResponse.Type.CONSOLE, messageList));
        agentOperator.processCall("[CONSOLE]", conversationId, messageList);
    }

    public String processDiscordRequest(String conversationId, List<Message> messageList) {
        agentOperator.publish(new AgentResponse(AgentResponse.Type.DISCORD, messageList));
        ChatResponse chatResponse = agentOperator.processCall("[DISCORD]", conversationId, messageList);
        if (chatResponse.getResult() != null) {
            return chatResponse.getResult().getOutput().getText();
        } else {
            return "No Response.";
        }
    }

    public String processTelegramRequest(String conversationId, List<Message> messageList) {
        agentOperator.publish(new AgentResponse(AgentResponse.Type.TELEGRAM, messageList));
        ChatResponse chatResponse = agentOperator.processCall("[TELEGRAM]", conversationId, messageList);
        if (chatResponse.getResult() != null) {
            return chatResponse.getResult().getOutput().getText();
        } else {
            return "No Response.";
        }
    }
}

