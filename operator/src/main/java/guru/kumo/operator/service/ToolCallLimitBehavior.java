package guru.kumo.operator.service;

import org.springframework.ai.model.tool.ToolCallLimitExceededException;

public enum ToolCallLimitBehavior {

    /**
     * Throw a {@link ToolCallLimitExceededException} carrying the tool calls executed so
     * far, aborting the current batch of tool calls immediately.
     */
    THROW,

    /**
     * Skip invoking the tool callback and instead return a
     * {@link org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse}
     * explaining that the limit was reached, letting the model see the rejection and
     * decide how to proceed.
     */
    RETURN_ERROR_RESPONSE

}
