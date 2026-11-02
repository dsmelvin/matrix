package guru.kumo.operator.configuration;

import guru.kumo.operator.util.EnvFileLoader;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import guru.kumo.operator.service.OperatorToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.image.GoogleGenAiImageConnectionDetails;
import org.springframework.ai.google.genai.image.GoogleGenAiImageModel;
import org.springframework.ai.google.genai.image.GoogleGenAiImageOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.retry.RetryTemplate;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class GoogleGenAiConfiguration {
    public static final String CHAT_MODEL_BEAN_NAME = "customGoogleGenAiChatModel";
    public static final String IMAGE_MODEL_BEAN_NAME = "customGoogleGenAiImageModel";

    // Why everything here is unconditional + lazy: in a GraalVM native image Spring evaluates @Conditional* at build
    // time, when the --env-file variables don't exist yet. A condition on AI_MODEL_CHAT & co. would therefore be frozen
    // to whatever the build machine had. The beans are always registered instead; AgentOperator picks the provider at
    // runtime and only then instantiates (and validates credentials of) the Google beans.
    @Bean
    @Lazy
    public GoogleCredentials credentials() throws IOException {
        return GoogleCredentials.fromStream(new FileInputStream(EnvFileLoader.getenv("GOOGLE_APPLICATION_CREDENTIALS")))
                .createScoped(EnvFileLoader.getenv("GOOGLE_GENAI_SCOPES"));
    }

    @Bean
    @Lazy
    public Client customGoogleGenAiClient(GoogleCredentials credentials) {
        return Client.builder()
                .project(EnvFileLoader.getenv("GOOGLE_CLOUD_PROJECT"))
                .location(EnvFileLoader.getenv("GOOGLE_CLOUD_LOCATION"))
                .vertexAI(true)
                .credentials(credentials)
                .build();
    }

    @Bean(name = CHAT_MODEL_BEAN_NAME, autowireCandidate = false)
    @Lazy
    public GoogleGenAiChatModel customGoogleGenAiChatModel(
            Client googleGenAiClient,
            ObjectProvider<RetryTemplate> retryTemplateProvider,
            ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(EnvFileLoader.getenv("GOOGLE_GENAI_CHAT_MODEL")).build();
        ToolCallingManager toolCallingManager = OperatorToolCallingManager.builder().build();
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
    @Lazy
    public GoogleGenAiImageConnectionDetails googleGenAiImageConnectionDetails(GoogleCredentials credentials, Client googleGenAiClient) {
        return GoogleGenAiImageConnectionDetails.builder()
                .credentials(credentials)
                .genAiClient(googleGenAiClient)
                .projectId(EnvFileLoader.getenv("GOOGLE_CLOUD_PROJECT"))
                .location(EnvFileLoader.getenv("GOOGLE_CLOUD_LOCATION"))
                .build();
    }

    @Bean(name = IMAGE_MODEL_BEAN_NAME, autowireCandidate = false)
    @Lazy
    public GoogleGenAiImageModel customGoogleGenAiImageModel(GoogleGenAiImageConnectionDetails connectionDetails) {
        GoogleGenAiImageOptions options = GoogleGenAiImageOptions.builder()
                .model(EnvFileLoader.getenv("GOOGLE_GENAI_IMAGE_MODEL"))
                .build();
        return new GoogleGenAiImageModel(connectionDetails, options);
    }
}