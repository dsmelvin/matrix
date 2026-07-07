package guru.kumo.operator.configuration;

import guru.kumo.operator.model.ChatCompletionRequest;
import guru.kumo.operator.model.ChatCompletionResponse;
import guru.kumo.operator.service.AgentOperatorService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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
                                ChatCompletionResponse chatCompletionResponse = jsonMapper.readValue(responseBodyString, ChatCompletionResponse.class);
                                agentOperatorService.publishInferencePayload(chatCompletionResponse);
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
