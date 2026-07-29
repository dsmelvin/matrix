package guru.kumo.operator.channel;

import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.tool.TodoWriteTool;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

public interface Channel {
    default void start(Thread thread, AgentOperatorService agentOperatorService) {
        agentOperatorService.subscribe().subscribe(agentInterfaceOutput -> {
            switch (agentInterfaceOutput.getType()) {
                case PREFILL -> prefillOutput(agentInterfaceOutput.getMessageList());
                case AGENT -> agent(agentInterfaceOutput.getLogPrefix(), agentInterfaceOutput.getChatResponse());
                case SUBAGENT ->
                        subagent(agentInterfaceOutput.getTaskCall(), agentInterfaceOutput.getSystemMessage(), agentInterfaceOutput.getUserMessage());
                case TODO -> todos(agentInterfaceOutput.getTodos());
                case TOOL_CALL ->
                        toolCallToString(agentInterfaceOutput.getLogPrefix(), agentInterfaceOutput.getToolCall());
                case TOOL_RESPONSE ->
                        toolResponseToString(agentInterfaceOutput.getLogPrefix(), agentInterfaceOutput.getToolResponse());
            }
        });
        thread.start();
    }

    void prefillOutput(List<Message> messageList);

    void agent(String logPrefix, ChatResponse chatResponse);

    void subagent(TaskCall taskCall, SystemMessage systemMessage, UserMessage userMessage);

    void todos(TodoWriteTool.Todos event);

    void toolCallToString(String logPrefix, AssistantMessage.ToolCall toolCall);

    void toolResponseToString(String logPrefix, ToolResponseMessage.ToolResponse toolResponse);

    void shutdown();
}
