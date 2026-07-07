package guru.kumo.operator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response payload for a chat completion call
 * (OpenAI-compatible / llama.cpp-server style, including "timings").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionResponse {

    private String id;

    private String object;

    private Long created;

    private String model;

    @JsonProperty("system_fingerprint")
    private String systemFingerprint;

    private List<Choice> choices;

    private Usage usage;

    private Timings timings;

    public ChatCompletionResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemFingerprint() {
        return systemFingerprint;
    }

    public void setSystemFingerprint(String systemFingerprint) {
        this.systemFingerprint = systemFingerprint;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public Timings getTimings() {
        return timings;
    }

    public void setTimings(Timings timings) {
        this.timings = timings;
    }

    // ---------------------------------------------------------------
    // Nested types
    // ---------------------------------------------------------------

    /**
     * A single completion choice.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {

        private Integer index;

        private ResponseMessage message;

        @JsonProperty("finish_reason")
        private String finishReason;

        public Choice() {
        }

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }

        public ResponseMessage getMessage() {
            return message;
        }

        public void setMessage(ResponseMessage message) {
            this.message = message;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }
    }

    /**
     * The assistant message returned in a choice.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseMessage {

        private String role;

        private String content;

        @JsonProperty("reasoning_content")
        private String reasoningContent;

        public ResponseMessage() {
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getReasoningContent() {
            return reasoningContent;
        }

        public void setReasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
        }
    }

    /**
     * Token usage summary.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {

        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        @JsonProperty("total_tokens")
        private Integer totalTokens;

        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;

        public Usage() {
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(Integer completionTokens) {
            this.completionTokens = completionTokens;
        }

        public Integer getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(Integer promptTokens) {
            this.promptTokens = promptTokens;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(Integer totalTokens) {
            this.totalTokens = totalTokens;
        }

        public PromptTokensDetails getPromptTokensDetails() {
            return promptTokensDetails;
        }

        public void setPromptTokensDetails(PromptTokensDetails promptTokensDetails) {
            this.promptTokensDetails = promptTokensDetails;
        }
    }

    /**
     * Breakdown of prompt token usage (e.g. cache hits).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptTokensDetails {

        @JsonProperty("cached_tokens")
        private Integer cachedTokens;

        public PromptTokensDetails() {
        }

        public Integer getCachedTokens() {
            return cachedTokens;
        }

        public void setCachedTokens(Integer cachedTokens) {
            this.cachedTokens = cachedTokens;
        }
    }

    /**
     * Inference server timing/performance metrics (llama.cpp server extension).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Timings {

        @JsonProperty("cache_n")
        private Integer cacheN;

        @JsonProperty("prompt_n")
        private Integer promptN;

        @JsonProperty("prompt_ms")
        private Double promptMs;

        @JsonProperty("prompt_per_token_ms")
        private Double promptPerTokenMs;

        @JsonProperty("prompt_per_second")
        private Double promptPerSecond;

        @JsonProperty("predicted_n")
        private Integer predictedN;

        @JsonProperty("predicted_ms")
        private Double predictedMs;

        @JsonProperty("predicted_per_token_ms")
        private Double predictedPerTokenMs;

        @JsonProperty("predicted_per_second")
        private Double predictedPerSecond;

        public Timings() {
        }

        public Integer getCacheN() {
            return cacheN;
        }

        public void setCacheN(Integer cacheN) {
            this.cacheN = cacheN;
        }

        public Integer getPromptN() {
            return promptN;
        }

        public void setPromptN(Integer promptN) {
            this.promptN = promptN;
        }

        public Double getPromptMs() {
            return promptMs;
        }

        public void setPromptMs(Double promptMs) {
            this.promptMs = promptMs;
        }

        public Double getPromptPerTokenMs() {
            return promptPerTokenMs;
        }

        public void setPromptPerTokenMs(Double promptPerTokenMs) {
            this.promptPerTokenMs = promptPerTokenMs;
        }

        public Double getPromptPerSecond() {
            return promptPerSecond;
        }

        public void setPromptPerSecond(Double promptPerSecond) {
            this.promptPerSecond = promptPerSecond;
        }

        public Integer getPredictedN() {
            return predictedN;
        }

        public void setPredictedN(Integer predictedN) {
            this.predictedN = predictedN;
        }

        public Double getPredictedMs() {
            return predictedMs;
        }

        public void setPredictedMs(Double predictedMs) {
            this.predictedMs = predictedMs;
        }

        public Double getPredictedPerTokenMs() {
            return predictedPerTokenMs;
        }

        public void setPredictedPerTokenMs(Double predictedPerTokenMs) {
            this.predictedPerTokenMs = predictedPerTokenMs;
        }

        public Double getPredictedPerSecond() {
            return predictedPerSecond;
        }

        public void setPredictedPerSecond(Double predictedPerSecond) {
            this.predictedPerSecond = predictedPerSecond;
        }
    }
}