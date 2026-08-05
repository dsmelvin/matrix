package guru.kumo.operator.channel.model;

import guru.kumo.operator.tool.TodoWriteTool;
import lombok.Getter;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

@Getter
public class AgentResponse {
    public enum Type {
        INIT,
        SUBAGENT,
        TOOL_CALL,
        TOOL_RESPONSE,
        TODO,
        AGENT,
        CONSOLE,
        DISCORD,
        TELEGRAM
    }

    private Type type;
    private String logPrefix;
    private ChatResponse chatResponse;
    private TodoWriteTool.Todos todos;
    private AssistantMessage.ToolCall toolCall;
    private ToolResponseMessage.ToolResponse toolResponse;
    private TaskCall taskCall;
    private SystemMessage systemMessage;
    private UserMessage userMessage;
    private List<Message> messageList;

    public AgentResponse(String logPrefix, ChatResponse chatResponse) {
        this.type = Type.AGENT;
        this.logPrefix = logPrefix;
        this.chatResponse = chatResponse;
    }

    public AgentResponse(String logPrefix, AssistantMessage.ToolCall toolCall) {
        this.type = Type.TOOL_CALL;
        this.logPrefix = logPrefix;
        this.toolCall = toolCall;
    }

    public AgentResponse(String logPrefix, ToolResponseMessage.ToolResponse toolResponse) {
        this.type = Type.TOOL_RESPONSE;
        this.logPrefix = logPrefix;
        this.toolResponse = toolResponse;
    }

    public AgentResponse(TodoWriteTool.Todos todos) {
        this.type = Type.TODO;
        this.todos = todos;
    }

    public AgentResponse(TaskCall taskCall, SystemMessage systemMessage, UserMessage userMessage) {
        this.type = Type.SUBAGENT;
        this.taskCall = taskCall;
        this.systemMessage = systemMessage;
        this.userMessage = userMessage;
    }

    public AgentResponse(List<Message> messageList) {
        this.type = Type.INIT;
        this.messageList = messageList;
    }

    public AgentResponse(Type type, List<Message> messageList) {
        this.type = type;
        this.messageList = messageList;
    }
}


