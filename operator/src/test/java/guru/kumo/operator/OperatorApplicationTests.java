package guru.kumo.operator;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles(profiles = {"test"})
@EnableAutoConfiguration(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
        GoogleGenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        GoogleGenAiImageConnectionAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class})
class OperatorApplicationTests {
    @Test
    void contextLoads() {
    }
}
