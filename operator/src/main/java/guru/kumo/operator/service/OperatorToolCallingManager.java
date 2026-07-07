package guru.kumo.operator.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationDocumentation;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@Slf4j
public final class OperatorToolCallingManager implements ToolCallingManager {
    private final JsonMapper jsonMapper = JacksonUtils.getDefaultJsonMapper();

    // @formatter:off
        private static final ObservationRegistry DEFAULT_OBSERVATION_REGISTRY
                = ObservationRegistry.NOOP;

        private static final ToolCallingObservationConvention DEFAULT_OBSERVATION_CONVENTION
                = new DefaultToolCallingObservationConvention();

        private static final ToolCallbackResolver DEFAULT_TOOL_CALLBACK_RESOLVER
                = new DelegatingToolCallbackResolver(List.of());

        private static final ToolExecutionExceptionProcessor DEFAULT_TOOL_EXECUTION_EXCEPTION_PROCESSOR
                = DefaultToolExecutionExceptionProcessor.builder().build();

        private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_START = "LLM may have adapted the tool name '";
        private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_END
                = "', especially if the name was truncated due to length limits. If this is the case, you can customize the prefixing and processing logic using McpToolNamePrefixGenerator";

        // @formatter:on

    /**
     * Default cap applied to every tool name unless overridden via
     * {@link guru.kumo.operator.service.OperatorToolCallingManager.Builder#maxCallsPerTool(String, int)}, exempted via
     * {@link guru.kumo.operator.service.OperatorToolCallingManager.Builder#excludeToolFromLimit(String)}, or disabled entirely via
     * {@link guru.kumo.operator.service.OperatorToolCallingManager.Builder#unlimitedCallsPerTool()}. Generous enough for legitimate iterative
     * tool use (pagination, retries) while still catching a runaway loop long before it
     * becomes expensive.
     */
    public static final int DEFAULT_MAX_CALLS_PER_TOOL = 40;

    /**
     * Default cap on the total number of tool calls, across all tools combined, allowed
     * within a turn, unless disabled entirely via
     * {@link guru.kumo.operator.service.OperatorToolCallingManager.Builder#unlimitedTotalToolCalls()}.
     */
    public static final int DEFAULT_MAX_TOTAL_TOOL_CALLS = 150;

    private final ObservationRegistry observationRegistry;

    private final ToolCallbackResolver toolCallbackResolver;

    private final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;

    private final ToolCallLimits toolCallLimits;

    private ToolCallingObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public OperatorToolCallingManager(ObservationRegistry observationRegistry, ToolCallbackResolver toolCallbackResolver,
                                      ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
        this(observationRegistry, toolCallbackResolver, toolExecutionExceptionProcessor,
                new ToolCallLimits(DEFAULT_MAX_CALLS_PER_TOOL, Map.of(), Set.of(), DEFAULT_MAX_TOTAL_TOOL_CALLS,
                        ToolCallLimitBehavior.THROW));
    }

    OperatorToolCallingManager(ObservationRegistry observationRegistry, ToolCallbackResolver toolCallbackResolver,
                               ToolExecutionExceptionProcessor toolExecutionExceptionProcessor, ToolCallLimits toolCallLimits) {
        Assert.notNull(observationRegistry, "observationRegistry cannot be null");
        Assert.notNull(toolCallbackResolver, "toolCallbackResolver cannot be null");
        Assert.notNull(toolExecutionExceptionProcessor, "toolCallExceptionConverter cannot be null");
        Assert.notNull(toolCallLimits, "toolCallLimits cannot be null");

        this.observationRegistry = observationRegistry;
        this.toolCallbackResolver = toolCallbackResolver;
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
        this.toolCallLimits = toolCallLimits;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        Assert.notNull(chatOptions, "chatOptions cannot be null");

        List<ToolCallback> toolCallbacks = new ArrayList<>(
                !CollectionUtils.isEmpty(chatOptions.getToolCallbacks()) ? chatOptions.getToolCallbacks() : List.of());

        return toolCallbacks.stream().map(ToolCallback::getToolDefinition).map(this::sanitize).toList();
    }

    private ToolDefinition sanitize(ToolDefinition def) {
        String cleaned = stripDefaults(def.inputSchema());
        return DefaultToolDefinition.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(cleaned)
                .build();
    }

    @SuppressWarnings("unchecked")
    private String stripDefaults(String schemaJson) {
        Object tree = jsonMapper.readValue(schemaJson, Object.class);
        strip(tree);
        return jsonMapper.writeValueAsString(tree);
    }

    @SuppressWarnings("unchecked")
    private void strip(Object node) {
        if (node instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).remove("default");
            map.values().forEach(this::strip);
        } else if (node instanceof List<?> list) {
            list.forEach(this::strip);
        }
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Assert.notNull(prompt, "prompt cannot be null");
        Assert.notNull(chatResponse, "chatResponse cannot be null");

        Optional<Generation> toolCallGeneration = chatResponse.getResults()
                .stream()
                .filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
                .findFirst();

        if (toolCallGeneration.isEmpty()) {
            throw new IllegalStateException("No tool call requested by the chat model");
        }

        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();

        ToolContext toolContext = buildToolContext(prompt, assistantMessage);

        guru.kumo.operator.service.OperatorToolCallingManager.InternalToolExecutionResult internalToolExecutionResult
                = executeToolCall(prompt, assistantMessage, toolContext);

        List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(prompt.getInstructions(),
                assistantMessage, internalToolExecutionResult.toolResponseMessage());

        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(internalToolExecutionResult.returnDirect())
                .build();
    }

    private static ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage) {
        Map<String, Object> toolContextMap = Map.of();

        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {
            toolContextMap = new HashMap<>(toolCallingChatOptions.getToolContext());
        }

        return new ToolContext(toolContextMap);
    }

    /**
     * Execute the tool call and return the response message.
     */
    private guru.kumo.operator.service.OperatorToolCallingManager.InternalToolExecutionResult executeToolCall(Prompt prompt, AssistantMessage assistantMessage,
                                                                                                              ToolContext toolContext) {
        List<ToolCallback> toolCallbacks = List.of();
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
            if (!CollectionUtils.isEmpty(toolCallingChatOptions.getToolCallbacks())) {
                toolCallbacks = toolCallingChatOptions.getToolCallbacks();
            }
        }

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        Boolean returnDirect = null;

        Map<String, Integer> toolCallCounts = ToolCallLimits.countPriorToolCalls(prompt.getInstructions());
        int totalToolCallCount = toolCallCounts.values().stream().mapToInt(Integer::intValue).sum();

        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {

            if (log.isDebugEnabled()) {
                log.debug("Executing tool call: " + toolCall.name());
            }

            String toolName = toolCall.name();

            totalToolCallCount++;
            int toolCallCount = toolCallCounts.merge(toolName, 1, Integer::sum);

            ToolCallLimits.Breach limitBreach = this.toolCallLimits.check(toolName, toolCallCount, totalToolCallCount);
            if (limitBreach != null) {
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, limitBreach.message()));

                if (this.toolCallLimits.onLimitExceeded() == ToolCallLimitBehavior.THROW) {
                    ToolResponseMessage partialToolResponseMessage = ToolResponseMessage.builder()
                            .responses(toolResponses)
                            .build();
                    List<Message> partialConversationHistory = buildConversationHistoryAfterToolExecution(
                            prompt.getInstructions(), assistantMessage, partialToolResponseMessage);
                    ToolExecutionResult partialToolExecutionResult = ToolExecutionResult.builder()
                            .conversationHistory(partialConversationHistory)
                            .returnDirect(Objects.requireNonNullElse(returnDirect, false))
                            .build();
                    throw new ToolCallLimitExceededException(limitBreach.toolName(), limitBreach.limit(),
                            partialToolExecutionResult);
                }

                // ToolCallLimitBehavior.RETURN_ERROR_RESPONSE: skip invoking this tool
                // call but keep processing the rest of the batch.
                continue;
            }

            String toolInputArguments = toolCall.arguments();

            // Handle the possible null parameter situation in streaming mode.
            final String finalToolInputArguments;
            if (!StringUtils.hasText(toolInputArguments)) {
                if (log.isWarnEnabled()) {
                    log.warn("Tool call arguments are null or empty for tool: " + toolName
                            + ". Using empty JSON object as default.");
                }
                finalToolInputArguments = "{}";
            } else {
                finalToolInputArguments = toolInputArguments;
            }

            ToolCallback toolCallback = toolCallbacks.stream()
                    .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                    .findFirst()
                    .orElseGet(() -> this.toolCallbackResolver.resolve(toolName));

            if (toolCallback == null) {
                if (log.isWarnEnabled()) {
                    log.warn(POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_START + toolName
                            + POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_END);
                }
                throw new IllegalStateException("No ToolCallback found for tool name: " + toolName);
            }

            if (returnDirect == null) {
                returnDirect = toolCallback.getToolMetadata().returnDirect();
            } else {
                returnDirect = returnDirect && toolCallback.getToolMetadata().returnDirect();
            }

            // In streaming/reactive mode the parent observation is propagated through the
            // Reactor context captured in ToolCallReactiveContextHolder. In blocking mode
            // that holder is never populated, so fall back to the observation currently
            // in scope on the calling thread to keep the observation hierarchy intact.
            Observation parent = ToolCallReactiveContextHolder.getContext()
                    .getOrDefault(ObservationThreadLocalAccessor.KEY, this.observationRegistry.getCurrentObservation());

            ToolCallingObservationContext observationContext = ToolCallingObservationContext.builder()
                    .toolDefinition(toolCallback.getToolDefinition())
                    .toolMetadata(toolCallback.getToolMetadata())
                    .toolCallId(toolCall.id())
                    .toolType(toolCall.type())
                    .toolCallArguments(finalToolInputArguments)
                    .build();

            String toolCallResult = ToolCallingObservationDocumentation.TOOL_CALL
                    .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                            this.observationRegistry)
                    .parentObservation(parent)
                    .observe(() -> {
                        String toolResult;
                        try {
                            toolResult = toolCallback.call(finalToolInputArguments, toolContext);
                        } catch (ToolExecutionException ex) {
                            toolResult = this.toolExecutionExceptionProcessor.process(ex);
                        } catch (Exception ex) {
                            toolResult = ex.getMessage();
                        }
                        observationContext.setToolCallResult(toolResult);
                        return toolResult;
                    });

            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                    toolCallResult != null ? toolCallResult : ""));
        }

        return new guru.kumo.operator.service.OperatorToolCallingManager.InternalToolExecutionResult(ToolResponseMessage.builder().responses(toolResponses).build(),
                Objects.requireNonNullElse(returnDirect, false));
    }

    private List<Message> buildConversationHistoryAfterToolExecution(List<Message> previousMessages,
                                                                     AssistantMessage assistantMessage, ToolResponseMessage toolResponseMessage) {
        List<Message> messages = new ArrayList<>(previousMessages);
        messages.add(assistantMessage);
        messages.add(toolResponseMessage);
        return messages;
    }

    public void setObservationConvention(ToolCallingObservationConvention observationConvention) {
        this.observationConvention = observationConvention;
    }

    public static guru.kumo.operator.service.OperatorToolCallingManager.Builder builder() {
        return new guru.kumo.operator.service.OperatorToolCallingManager.Builder();
    }

    private record InternalToolExecutionResult(ToolResponseMessage toolResponseMessage, boolean returnDirect) {
    }

    public final static class Builder {

        private ObservationRegistry observationRegistry = DEFAULT_OBSERVATION_REGISTRY;

        private ToolCallbackResolver toolCallbackResolver = DEFAULT_TOOL_CALLBACK_RESOLVER;

        private ToolExecutionExceptionProcessor toolExecutionExceptionProcessor = DEFAULT_TOOL_EXECUTION_EXCEPTION_PROCESSOR;

        private int defaultMaxCallsPerTool = DEFAULT_MAX_CALLS_PER_TOOL;

        private final Map<String, Integer> maxCallsPerTool = new HashMap<>();

        private final Set<String> toolsExcludedFromLimit = new HashSet<>();

        private int maxTotalToolCalls = DEFAULT_MAX_TOTAL_TOOL_CALLS;

        private ToolCallLimitBehavior onLimitExceeded = ToolCallLimitBehavior.THROW;

        private Builder() {
        }

        public guru.kumo.operator.service.OperatorToolCallingManager.Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public guru.kumo.operator.service.OperatorToolCallingManager.Builder toolCallbackResolver(ToolCallbackResolver toolCallbackResolver) {
            this.toolCallbackResolver = toolCallbackResolver;
            return this;
        }

        public guru.kumo.operator.service.OperatorToolCallingManager.Builder toolExecutionExceptionProcessor(
                ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
            this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
            return this;
        }

        /**
         * Default maximum number of times any single tool can be called within a turn,
         * unless overridden for a specific tool name via
         * {@link #maxCallsPerTool(String, int)} or exempted via
         * {@link #excludeToolFromLimit(String)}. Defaults to
         * {@link guru.kumo.operator.service.OperatorToolCallingManager#DEFAULT_MAX_CALLS_PER_TOOL}; call
         * {@link #unlimitedCallsPerTool()} to disable this limit entirely.
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder maxCallsPerTool(int maxCallsPerTool) {
            Assert.isTrue(maxCallsPerTool > 0, "maxCallsPerTool must be greater than 0");
            this.defaultMaxCallsPerTool = maxCallsPerTool;
            return this;
        }

        /**
         * Disable the per-tool call limit entirely, undoing {@link #maxCallsPerTool(int)}
         * (or the {@link guru.kumo.operator.service.OperatorToolCallingManager#DEFAULT_MAX_CALLS_PER_TOOL} default),
         * so that, absent a specific {@link #maxCallsPerTool(String, int)} override, any
         * tool can be called an unlimited number of times.
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder unlimitedCallsPerTool() {
            this.defaultMaxCallsPerTool = ToolCallLimits.NO_LIMIT;
            return this;
        }

        /**
         * Maximum number of times the named tool can be called within a turn, overriding
         * the default set via {@link #maxCallsPerTool(int)} for that tool only.
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder maxCallsPerTool(String toolName, int maxCallsPerTool) {
            Assert.hasText(toolName, "toolName cannot be null or empty");
            Assert.isTrue(maxCallsPerTool > 0, "maxCallsPerTool must be greater than 0");
            this.maxCallsPerTool.put(toolName, maxCallsPerTool);
            this.toolsExcludedFromLimit.remove(toolName);
            return this;
        }

        /**
         * Exempt the named tool from the per-tool call limit, so it can be called an
         * unlimited number of times regardless of {@link #maxCallsPerTool(int)} or a
         * prior {@link #maxCallsPerTool(String, int)} override for that tool. Calls to
         * this tool still count toward {@link #maxTotalToolCalls(int)}.
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder excludeToolFromLimit(String toolName) {
            Assert.hasText(toolName, "toolName cannot be null or empty");
            this.toolsExcludedFromLimit.add(toolName);
            this.maxCallsPerTool.remove(toolName);
            return this;
        }

        /**
         * Maximum number of tool calls, across all tools combined, allowed within a turn.
         * Defaults to {@link guru.kumo.operator.service.OperatorToolCallingManager#DEFAULT_MAX_TOTAL_TOOL_CALLS};
         * call {@link #unlimitedTotalToolCalls()} to disable this limit entirely.
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder maxTotalToolCalls(int maxTotalToolCalls) {
            Assert.isTrue(maxTotalToolCalls > 0, "maxTotalToolCalls must be greater than 0");
            this.maxTotalToolCalls = maxTotalToolCalls;
            return this;
        }

        /**
         * Disable the total tool call limit entirely, undoing
         * {@link #maxTotalToolCalls(int)} (or the
         * {@link guru.kumo.operator.service.OperatorToolCallingManager#DEFAULT_MAX_TOTAL_TOOL_CALLS} default).
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder unlimitedTotalToolCalls() {
            this.maxTotalToolCalls = ToolCallLimits.NO_LIMIT;
            return this;
        }

        /**
         * What to do when a configured limit is exceeded. Defaults to
         * {@link ToolCallLimitBehavior#THROW}.
         */
        public guru.kumo.operator.service.OperatorToolCallingManager.Builder onLimitExceeded(ToolCallLimitBehavior onLimitExceeded) {
            Assert.notNull(onLimitExceeded, "onLimitExceeded cannot be null");
            this.onLimitExceeded = onLimitExceeded;
            return this;
        }

        public guru.kumo.operator.service.OperatorToolCallingManager build() {
            ToolCallLimits toolCallLimits = new ToolCallLimits(this.defaultMaxCallsPerTool, this.maxCallsPerTool,
                    this.toolsExcludedFromLimit, this.maxTotalToolCalls, this.onLimitExceeded);
            return new guru.kumo.operator.service.OperatorToolCallingManager(this.observationRegistry, this.toolCallbackResolver,
                    this.toolExecutionExceptionProcessor, toolCallLimits);
        }

    }

}

