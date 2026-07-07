package guru.kumo.operator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Root request payload for a chat completion call
 * (OpenAI-compatible / tool-calling format).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {

    private List<Message> messages;

    private String model;

    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    private List<Tool> tools;

    public ChatCompletionRequest() {
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public void setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }

    // ---------------------------------------------------------------
    // Nested types
    // ---------------------------------------------------------------

    /**
     * A single chat message.
     *
     * <p>{@code content} is polymorphic per the OpenAI-compatible spec:
     * it may be a plain string, or a list of content parts (text /
     * image_url) for multimodal messages. Assistant messages may also
     * carry {@code tool_calls}; tool-role messages carry
     * {@code tool_call_id}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {

        private String role;

        @JsonSerialize(using = MessageContentSerializer.class)
        @JsonDeserialize(using = MessageContentDeserializer.class)
        private MessageContent content;

        private String name;

        @JsonProperty("tool_call_id")
        private String toolCallId;

        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;

        @JsonProperty("reasoning_content")
        private String reasoningContent;

        @JsonCreator
        public Message() {
        }

        public Message(String role, String content) {
            this.role = role;
            this.content = content == null ? null : new MessageContent(content);
        }

        public Message(String role, MessageContent content) {
            this.role = role;
            this.content = content;
        }

        public Message(String role, List<ContentPart> parts) {
            this.role = role;
            this.content = parts == null ? null : new MessageContent(parts);
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public MessageContent getContent() {
            return content;
        }

        public void setContent(MessageContent content) {
            this.content = content;
        }

        /** Convenience accessor: returns the plain-text content if the
         *  message content is a simple string, or the concatenation of
         *  all "text" parts if it is multimodal. Returns null if there
         *  is no content. */
        @JsonIgnore
        public String getContentAsText() {
            return content == null ? null : content.asText();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public void setToolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
        }

        public String getReasoningContent() {
            return reasoningContent;
        }

        public void setReasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
        }
    }

    /**
     * Wrapper holding either a plain string or a list of {@link ContentPart}.
     * Exactly one of the two fields is populated.
     */
    @JsonSerialize(using = MessageContentSerializer.class)
    @JsonDeserialize(using = MessageContentDeserializer.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MessageContent {

        private String text;

        private List<ContentPart> parts;

        public MessageContent() {
        }

        public MessageContent(String text) {
            this.text = text;
        }

        public MessageContent(List<ContentPart> parts) {
            this.parts = parts;
        }

        public boolean isText() {
            return text != null;
        }

        public boolean isParts() {
            return parts != null;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public List<ContentPart> getParts() {
            return parts;
        }

        public void setParts(List<ContentPart> parts) {
            this.parts = parts;
        }

        /** Flattened plain-text view, regardless of underlying shape. */
        public String asText() {
            if (text != null) {
                return text;
            }
            if (parts == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (ContentPart part : parts) {
                if (part.getText() != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.getText());
                }
            }
            return sb.toString();
        }
    }

    /**
     * A single part of a multimodal message content array, e.g.
     * {"type": "text", "text": "..."} or
     * {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentPart {

        private String type;

        private String text;

        @JsonProperty("image_url")
        private ImageUrl imageUrl;

        public ContentPart() {
        }

        public ContentPart(String type, String text) {
            this.type = type;
            this.text = text;
        }

        public ContentPart(String type, ImageUrl imageUrl) {
            this.type = type;
            this.imageUrl = imageUrl;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public ImageUrl getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(ImageUrl imageUrl) {
            this.imageUrl = imageUrl;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageUrl {

        private String url;

        public ImageUrl() {
        }

        public ImageUrl(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    /**
     * A tool call emitted by the assistant (id + function name/arguments).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {

        private String id;

        private String type;

        private ToolCallFunction function;

        public ToolCall() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public ToolCallFunction getFunction() {
            return function;
        }

        public void setFunction(ToolCallFunction function) {
            this.function = function;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallFunction {

        private String name;

        private String arguments;

        public ToolCallFunction() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }

    /**
     * A tool definition exposed to the model.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {

        private String type;

        private FunctionDefinition function;

        public Tool() {
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public FunctionDefinition getFunction() {
            return function;
        }

        public void setFunction(FunctionDefinition function) {
            this.function = function;
        }
    }

    /**
     * Describes a callable function exposed by a tool.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionDefinition {

        private String name;

        private String description;

        private JsonSchema parameters;

        private Boolean strict;

        public FunctionDefinition() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public JsonSchema getParameters() {
            return parameters;
        }

        public void setParameters(JsonSchema parameters) {
            this.parameters = parameters;
        }

        public Boolean getStrict() {
            return strict;
        }

        public void setStrict(Boolean strict) {
            this.strict = strict;
        }
    }

    /**
     * A JSON Schema fragment (used both for a tool's top-level "parameters"
     * object and for each individual property within it).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonSchema {

        @JsonProperty("$schema")
        private String schema;

        private String type;

        private String description;

        private String format;

        private Map<String, JsonSchema> properties;

        private JsonSchema items;

        private List<String> required;

        @JsonProperty("additionalProperties")
        @JsonDeserialize(using = AdditionalPropertiesDeserializer.class)
        private Object additionalProperties;

        private JsonSchema propertyNames;

        @JsonProperty("enum")
        private List<String> enumValues;

        public JsonSchema() {
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public Map<String, JsonSchema> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, JsonSchema> properties) {
            this.properties = properties;
        }

        public JsonSchema getItems() {
            return items;
        }

        public void setItems(JsonSchema items) {
            this.items = items;
        }

        public List<String> getRequired() {
            return required;
        }

        public void setRequired(List<String> required) {
            this.required = required;
        }

        public Object getAdditionalProperties() {
            return additionalProperties;
        }

        public void setAdditionalProperties(Object additionalProperties) {
            this.additionalProperties = additionalProperties;
        }

        @JsonIgnore
        public Boolean isAdditionalPropertiesAllowed() {
            if (additionalProperties instanceof Boolean b) {
                return b;
            }
            return additionalProperties != null;
        }

        @JsonIgnore
        public JsonSchema getAdditionalPropertiesSchema() {
            if (additionalProperties instanceof JsonSchema s) {
                return s;
            }
            return null;
        }

        public JsonSchema getPropertyNames() {
            return propertyNames;
        }

        public void setPropertyNames(JsonSchema propertyNames) {
            this.propertyNames = propertyNames;
        }

        public List<String> getEnumValues() {
            return enumValues;
        }

        public void setEnumValues(List<String> enumValues) {
            this.enumValues = enumValues;
        }
    }

    // ---------------------------------------------------------------
    // Custom serializer / deserializer for the polymorphic "additionalProperties" field
    // ---------------------------------------------------------------

    public static class AdditionalPropertiesDeserializer extends ValueDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonNode node = ctxt.readTree(p);
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isObject()) {
                return ctxt.readTreeAsValue(node, JsonSchema.class);
            }
            return ctxt.readTreeAsValue(node, Object.class);
        }
    }

    // ---------------------------------------------------------------
    // Custom serializer / deserializer for the polymorphic "content" field
    // ---------------------------------------------------------------

    public static class MessageContentSerializer extends ValueSerializer<MessageContent> {
        @Override
        public void serialize(MessageContent value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value == null) {
                gen.writeNull();
            } else if (value.isText()) {
                gen.writeString(value.getText());
            } else if (value.getParts() != null) {
                ctxt.writeValue(gen, value.getParts());
            } else {
                gen.writeNull();
            }
        }
    }

    public static class MessageContentDeserializer extends ValueDeserializer<MessageContent> {
        @Override
        public MessageContent deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonNode node = ctxt.readTree(p);
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isString()) {
                return new MessageContent(node.asString());
            }
            if (node.isArray()) {
                List<ContentPart> parts = new ArrayList<>();
                for (JsonNode partNode : node) {
                    ContentPart part = ctxt.readTreeAsValue(partNode, ContentPart.class);
                    parts.add(part);
                }
                return new MessageContent(parts);
            }
            // Fallback: unexpected shape, coerce to string
            return new MessageContent(node.asString());
        }
    }
}