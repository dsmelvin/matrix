package guru.kumo.operator.service;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.DefaultToolCallingManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ToolCallLimits {

    static final int NO_LIMIT = -1;

    private final int defaultMaxCallsPerTool;

    private final Map<String, Integer> maxCallsPerTool;

    private final Set<String> toolsExcludedFromLimit;

    private final int maxTotalToolCalls;

    private final ToolCallLimitBehavior onLimitExceeded;

    ToolCallLimits(int defaultMaxCallsPerTool, Map<String, Integer> maxCallsPerTool, Set<String> toolsExcludedFromLimit,
                   int maxTotalToolCalls, ToolCallLimitBehavior onLimitExceeded) {
        this.defaultMaxCallsPerTool = defaultMaxCallsPerTool;
        this.maxCallsPerTool = Map.copyOf(maxCallsPerTool);
        this.toolsExcludedFromLimit = Set.copyOf(toolsExcludedFromLimit);
        this.maxTotalToolCalls = maxTotalToolCalls;
        this.onLimitExceeded = onLimitExceeded;
    }

    ToolCallLimitBehavior onLimitExceeded() {
        return this.onLimitExceeded;
    }

    /**
     * Tally tool calls already executed earlier in this turn by scanning the
     * {@link ToolResponseMessage.ToolResponse} entries present in the current turn's
     * portion of the conversation history. The
     * {@link org.springframework.ai.chat.prompt.Prompt} passed into
     * {@link DefaultToolCallingManager#executeToolCalls} accumulates every prior round's
     * assistant and tool response messages (see {@code ToolCallingAdvisor}'s tool-calling
     * loop), so this reconstructs the running count without the manager itself holding
     * any per-turn state. Only messages from the current turn - the last
     * {@link UserMessage} onward, matching the Turn definition used by the
     * {@code spring-ai-session} project - are counted, so a long conversation replayed in
     * full by an external {@code ChatMemory} advisor does not inflate the count with tool
     * calls from earlier turns.
     */
    static Map<String, Integer> countPriorToolCalls(List<Message> instructions) {
        Map<String, Integer> counts = new HashMap<>();
        for (Message message : currentTurnMessages(instructions)) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    counts.merge(response.name(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * The current, still-open turn: the last {@link UserMessage} in {@code instructions}
     * plus everything after it. Falls back to the full list if no {@link UserMessage} is
     * present.
     */
    private static List<Message> currentTurnMessages(List<Message> instructions) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage) {
                return instructions.subList(i, instructions.size());
            }
        }
        return instructions;
    }

    /**
     * Checks the given call counts (inclusive of the call about to be made) against the
     * configured limits.
     *
     * @return a {@link guru.kumo.operator.service.ToolCallLimits.Breach} describing the limit that was hit, or {@code null} if the
     * call is within all configured limits.
     */
    guru.kumo.operator.service.ToolCallLimits.Breach check(String toolName, int toolCallCount, int totalToolCallCount) {
        if (!this.toolsExcludedFromLimit.contains(toolName)) {
            int maxCallsForTool = this.maxCallsPerTool.getOrDefault(toolName, this.defaultMaxCallsPerTool);
            if (maxCallsForTool != NO_LIMIT && toolCallCount > maxCallsForTool) {
                return new guru.kumo.operator.service.ToolCallLimits.Breach(toolName, maxCallsForTool,
                        "Tool call limit (%d) exceeded for tool '%s'. No further calls to this tool are allowed in this turn."
                                .formatted(maxCallsForTool, toolName));
            }
        }

        if (this.maxTotalToolCalls != NO_LIMIT && totalToolCallCount > this.maxTotalToolCalls) {
            return new guru.kumo.operator.service.ToolCallLimits.Breach(null, this.maxTotalToolCalls,
                    "Total tool call limit (%d) exceeded for this turn. No further tool calls are allowed."
                            .formatted(this.maxTotalToolCalls));
        }

        return null;
    }

    record Breach(@Nullable String toolName, int limit, String message) {
    }

}
