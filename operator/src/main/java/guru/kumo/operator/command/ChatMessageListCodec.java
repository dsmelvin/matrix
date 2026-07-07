package guru.kumo.operator.command;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media; // NOTE: verify this package for your Spring AI version —
// Media has lived under org.springframework.ai.model
// and org.springframework.ai.content across releases.
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes/deserializes {@code List<org.springframework.ai.chat.messages.Message>} to/from JSON,
 * for Spring AI 2.0+ / Jackson 3 (tools.jackson).
 *
 * Message is an interface implemented by SystemMessage, UserMessage, AssistantMessage,
 * and ToolResponseMessage, so plain bean serialization can't round-trip it without help.
 * This registers a custom (de)serializer that tags each message with its MessageType and
 * reconstructs the right concrete subclass on the way back in.
 *
 * Jackson 3 notes vs. the old Jackson 2 version of this class:
 *  - ObjectMapper -> JsonMapper (built via JsonMapper.builder(), immutable once built)
 *  - Custom (de)serializers extend ValueSerializer / ValueDeserializer, not
 *    JsonSerializer / JsonDeserializer
 *  - serialize()/deserialize() take a SerializationContext / DeserializationContext, not a
 *    SerializerProvider
 *  - Checked JsonProcessingException is gone; failures surface as unchecked JacksonException
 *  - @JsonProperty and friends are unaffected: annotations still live under
 *    com.fasterxml.jackson.annotation, shared between Jackson 2 and 3
 *
 * NOTE: Spring AI 2.0 moved multi-arg construction of AssistantMessage and ToolResponseMessage
 * behind builders (their old (content, metadata, ...) constructors are now protected/removed,
 * matching the immutable-builder pattern Spring AI adopted across its message and options
 * classes in 2.0). If method names below (metadata(), toolCalls(), responses(), media()) don't
 * match your exact Spring AI 2.0.x patch version, check the builder's available methods via
 * autocomplete — these APIs were still shifting across 2.0 milestones as of this writing. The
 * same caveat applies to Media.Builder's method names (id(), mimeType(), name(), data()).
 */
public class ChatMessageListCodec {

    private final JsonMapper jsonMapper;

    public ChatMessageListCodec() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Message.class, new MessageSerializer());
        module.addDeserializer(Message.class, new MessageDeserializer());

        this.jsonMapper = JsonMapper.builder()
                .addModule(module)
                .build();
    }

    public String serialize(List<Message> messages) {
        return jsonMapper.writeValueAsString(messages);
    }

    public List<Message> deserialize(String json) {
        return jsonMapper.readValue(json, new TypeReference<List<Message>>() {});
    }

    private static class MessageSerializer extends ValueSerializer<Message> {

        @Override
        public void serialize(Message message, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            gen.writeStringProperty("messageType", message.getMessageType().name());
            gen.writeStringProperty("text", message.getText());

            if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
                gen.writePOJOProperty("metadata", message.getMetadata());
            }

            if (message instanceof AssistantMessage assistantMessage) {
                if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
                    gen.writePOJOProperty("toolCalls", assistantMessage.getToolCalls());
                }
            }
            else if (message instanceof ToolResponseMessage toolResponseMessage) {
                gen.writePOJOProperty("responses", toolResponseMessage.getResponses());
            }
            else if (message instanceof UserMessage userMessage) {
                if (userMessage.getMedia() != null && !userMessage.getMedia().isEmpty()) {
                    gen.writeArrayPropertyStart("media");
                    for (Media media : userMessage.getMedia()) {
                        writeMedia(media, gen);
                    }
                    gen.writeEndArray();
                }
            }

            gen.writeEndObject();
        }

        private void writeMedia(Media media, JsonGenerator gen) {
            gen.writeStartObject();

            if (media.getId() != null) {
                gen.writeStringProperty("id", media.getId());
            }
            if (media.getMimeType() != null) {
                gen.writeStringProperty("mimeType", media.getMimeType().toString());
            }
            if (media.getName() != null) {
                gen.writeStringProperty("name", media.getName());
            }

            Object data = media.getData();
            if (data instanceof byte[] bytes) {
                gen.writeStringProperty("dataEncoding", "base64");
                gen.writeStringProperty("data", Base64.getEncoder().encodeToString(bytes));
            }
            else if (data != null) {
                // Typically a URL/URI pointing at externally-hosted media.
                gen.writeStringProperty("dataEncoding", "uri");
                gen.writeStringProperty("data", data.toString());
            }

            gen.writeEndObject();
        }
    }

    private static class MessageDeserializer extends ValueDeserializer<Message> {

        @Override
        public Message deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonNode node = ctxt.readTree(p);

            String messageTypeStr = node.get("messageType").asString();
            String text = (node.has("text") && !node.get("text").isNull()) ? node.get("text").asString() : "";

            JavaType metadataType = ctxt.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class);
            Map<String, Object> metadata = node.has("metadata")
                    ? ctxt.readTreeAsValue(node.get("metadata"), metadataType)
                    : new HashMap<>();

            MessageType type = MessageType.valueOf(messageTypeStr);

            switch (type) {
                case SYSTEM:
                    return new SystemMessage(text);

                case USER:
                    List<Media> media = new ArrayList<>();
                    if (node.has("media")) {
                        for (JsonNode mediaNode : node.get("media")) {
                            media.add(readMedia(mediaNode));
                        }
                    }
                    return UserMessage.builder()
                            .text(text)
                            .metadata(metadata)
                            .media(media)
                            .build();

                case ASSISTANT:
                    JavaType toolCallListType = ctxt.getTypeFactory()
                            .constructCollectionType(ArrayList.class, AssistantMessage.ToolCall.class);
                    List<AssistantMessage.ToolCall> toolCalls = node.has("toolCalls")
                            ? ctxt.readTreeAsValue(node.get("toolCalls"), toolCallListType)
                            : new ArrayList<>();
                    return AssistantMessage.builder()
                            .content(text)
                            .properties(metadata)
                            .toolCalls(toolCalls)
                            .build();

                case TOOL:
                    JavaType responseListType = ctxt.getTypeFactory()
                            .constructCollectionType(ArrayList.class, ToolResponseMessage.ToolResponse.class);
                    List<ToolResponseMessage.ToolResponse> responses = node.has("responses")
                            ? ctxt.readTreeAsValue(node.get("responses"), responseListType)
                            : new ArrayList<>();
                    return ToolResponseMessage.builder()
                            .responses(responses)
                            .metadata(metadata)
                            .build();

                default:
                    throw new IllegalArgumentException("Unsupported message type: " + messageTypeStr);
            }
        }

        private Media readMedia(JsonNode mediaNode) {
            String id = mediaNode.has("id") ? mediaNode.get("id").asString() : null;
            String mimeTypeStr = mediaNode.has("mimeType") ? mediaNode.get("mimeType").asString() : null;
            String name = mediaNode.has("name") ? mediaNode.get("name").asString() : null;
            String encoding = mediaNode.has("dataEncoding") ? mediaNode.get("dataEncoding").asString() : "uri";
            String dataStr = mediaNode.has("data") ? mediaNode.get("data").asString() : null;

            Media.Builder builder = Media.builder();
            if (id != null) {
                builder.id(id);
            }
            if (mimeTypeStr != null) {
                builder.mimeType(MimeType.valueOf(mimeTypeStr));
            }
            if (name != null) {
                builder.name(name);
            }

            if (dataStr != null) {
                if ("base64".equals(encoding)) {
                    builder.data(Base64.getDecoder().decode(dataStr));
                }
                else {
                    try {
                        builder.data(URI.create(dataStr).toURL());
                    }
                    catch (Exception e) {
                        // Not a resolvable URL (e.g. a data: URI or opaque identifier) —
                        // fall back to the raw string so no data is lost.
                        builder.data(dataStr);
                    }
                }
            }

            return builder.build();
        }
    }
}