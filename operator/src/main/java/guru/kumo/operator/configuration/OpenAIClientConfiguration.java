package guru.kumo.operator.configuration;

import guru.kumo.operator.model.ChatCompletionRequest;
import guru.kumo.operator.model.ChatCompletionResponse;
import guru.kumo.operator.service.AgentOperatorService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Slf4j
@Configuration
public class OpenAIClientConfiguration {
    private final static JsonMapper jsonMapper = new JsonMapper();

    @Bean
    public OpenAiHttpClientBuilderCustomizer openAiHttpClientBuilderCustomizer(@Lazy AgentOperatorService agentOperatorService) {
        return new OpenAiHttpClientBuilderCustomizer() {
            @Override
            public void customize(SpringAiOpenAiHttpClient.Builder builder) {
                builder.interceptor(new Interceptor() {
                    @Override
                    public @NonNull Response intercept(Interceptor.@NonNull Chain chain) throws IOException {
                        Request request = chain.request();
                        if (request.body() != null) {
                            Buffer buffer = new Buffer();
                            request.body().writeTo(buffer);
                            String requestBodyString = buffer.readUtf8();
                            try {
                                ChatCompletionRequest chatCompletionRequest = jsonMapper.readValue(requestBodyString, ChatCompletionRequest.class);
                                agentOperatorService.publishInferencePayload(chatCompletionRequest);
                            } catch (Exception e) {
                                log.error("Failed to deserialize ChatCompletionRequest payload: {}", e.getMessage(), e);
                            }
                        }

                        Response response = chain.proceed(request);

                        ResponseBody responseBody = response.body();
                        if (responseBody != null) {
                            BufferedSource source = responseBody.source();
                            source.request(Long.MAX_VALUE); // Buffer the entire body
                            Buffer buffer = source.getBuffer().clone();
                            String responseBodyString = buffer.readUtf8();

                            try {
                                MediaType contentType = responseBody.contentType();
                                if (contentType != null && contentType.type().equals("application") && contentType.subtype().equals("json")) {
                                    ChatCompletionResponse chatCompletionResponse = jsonMapper.readValue(responseBodyString, ChatCompletionResponse.class);
                                    agentOperatorService.publishInferencePayload(chatCompletionResponse);
                                } else if (contentType != null && contentType.type().equals("text") && contentType.subtype().equals("event-stream")) {
                                    for (String token : responseBodyString.split("\n")) {
                                        if (!StringUtils.hasLength(token.trim())) continue;
                                        if (token.trim().startsWith("data: [DONE]")) break;
                                        if (!token.trim().startsWith("data:")) continue;
                                        ChatCompletionResponse chatCompletionResponse = jsonMapper.readValue(token.substring("data:".length()), ChatCompletionResponse.class);
                                        agentOperatorService.publishInferencePayload(chatCompletionResponse);
                                    }
                                }
                            } catch (Exception e) {
                                log.error("Failed to deserialize ChatCompletionResponse payload: {}", e.getMessage(), e);
                            }
                        }
                        return response;
                    }
                });
            }
        };
    }
}
