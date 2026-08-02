package guru.kumo.operator.configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.image.GoogleGenAiImageConnectionDetails;
import org.springframework.ai.google.genai.image.GoogleGenAiImageModel;
import org.springframework.ai.google.genai.image.GoogleGenAiImageOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryTemplate;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class GoogleGenAiConfig {
    @Bean
    @ConditionalOnExpression("#{systemEnvironment.containsKey('GOOGLE_APPLICATION_CREDENTIALS') && systemEnvironment.containsKey('GOOGLE_GENAI_SCOPES')}")
    public GoogleCredentials credentials() throws IOException {
        return GoogleCredentials.fromStream(new FileInputStream(System.getenv("GOOGLE_APPLICATION_CREDENTIALS")))
                .createScoped(System.getenv("GOOGLE_GENAI_SCOPES"));
    }

    @Bean
    @ConditionalOnBean(GoogleCredentials.class)
    public Client customGoogleGenAiClient(GoogleCredentials credentials) {
        return Client.builder()
                .project(System.getenv("GOOGLE_CLOUD_PROJECT"))
                .location(System.getenv("GOOGLE_CLOUD_LOCATION"))
                .vertexAI(true)
                .credentials(credentials)
                .build();
    }

    @Bean
    @ConditionalOnBean(GoogleCredentials.class)
    @ConditionalOnExpression("#{systemEnvironment.containsKey('AI_MODEL_CHAT') && systemEnvironment['AI_MODEL_CHAT'] == 'google-genai'}")
    public GoogleGenAiChatModel googleGenAiChatModel(
            Client googleGenAiClient,
            ObjectProvider<RetryTemplate> retryTemplateProvider,
            ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(System.getenv("GOOGLE_GENAI_CHAT_MODEL")).build();
        ToolCallingManager toolCallingManager = toolCallingManagerProvider.getIfAvailable(() -> null);
        RetryTemplate retryTemplate = retryTemplateProvider.getIfAvailable(RetryTemplate::new);
        ObservationRegistry observationRegistry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);

        return GoogleGenAiChatModel.builder()
                .genAiClient(googleGenAiClient)
                .options(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    @ConditionalOnBean(GoogleCredentials.class)
    @ConditionalOnExpression("#{systemEnvironment.containsKey('AI_MODEL_IMAGE') && systemEnvironment['AI_MODEL_IMAGE'] == 'google-genai'}")
    public GoogleGenAiImageConnectionDetails googleGenAiImageConnectionDetails(GoogleCredentials credentials, Client googleGenAiClient) {
        return GoogleGenAiImageConnectionDetails.builder()
                .credentials(credentials)
                .genAiClient(googleGenAiClient)
                .projectId(System.getenv("GOOGLE_CLOUD_PROJECT"))
                .location(System.getenv("GOOGLE_CLOUD_LOCATION"))
                .build();
    }

    @Bean
    @ConditionalOnBean(GoogleCredentials.class)
    @ConditionalOnExpression("#{systemEnvironment.containsKey('AI_MODEL_IMAGE') && systemEnvironment['AI_MODEL_IMAGE'] == 'google-genai'}")
    public GoogleGenAiImageModel googleGenAiImageModel(GoogleGenAiImageConnectionDetails connectionDetails) {
        GoogleGenAiImageOptions options = GoogleGenAiImageOptions.builder()
                .model(System.getenv("GOOGLE_GENAI_IMAGE_MODEL"))
                .build();
        return new GoogleGenAiImageModel(connectionDetails, options);
    }
}