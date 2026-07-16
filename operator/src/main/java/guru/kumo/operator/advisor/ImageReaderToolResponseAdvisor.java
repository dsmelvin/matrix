package guru.kumo.operator.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

import java.util.List;
import java.util.function.Function;

public class ImageReaderToolResponseAdvisor implements CallAdvisor {
    private final Function<ToolResponseMessage, Message> responseConverter;
    private final String name;
    private final int order;

    private ImageReaderToolResponseAdvisor(Builder builder) {
        this.responseConverter = builder.responseConverter;
        this.name = builder.name;
        this.order = builder.order;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        List<Message> rewritten = chatClientRequest.prompt().getInstructions().stream()
                .map(this::maybeTransform)
                .toList();

        ChatClientRequest patched = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(rewritten).build())
                .build();

        return callAdvisorChain.nextCall(patched);
    }

    private Message maybeTransform(Message msg) {
        if (msg instanceof ToolResponseMessage toolResponse) {
            return this.responseConverter.apply(toolResponse);
        }
        return msg;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    public static class Builder {

        private Function<ToolResponseMessage, Message> responseConverter = msg -> msg;
        private String name = ImageReaderToolResponseAdvisor.class.getSimpleName();
        private int order = Ordered.HIGHEST_PRECEDENCE + 400; // inside ToolCallingAdvisor's loop (300) by default

        private Builder() {
        }

        public Builder responseConverter(Function<ToolResponseMessage, Message> responseConverter) {
            Assert.notNull(responseConverter, "responseConverter cannot be null");
            this.responseConverter = responseConverter;
            return this;
        }

        public Builder name(String name) {
            Assert.hasText(name, "name cannot be empty");
            this.name = name;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public ImageReaderToolResponseAdvisor build() {
            return new ImageReaderToolResponseAdvisor(this);
        }
    }
}