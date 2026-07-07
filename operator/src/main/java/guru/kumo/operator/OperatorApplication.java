package guru.kumo.operator;

import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {GoogleGenAiImageConnectionAutoConfiguration.class})
public class OperatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OperatorApplication.class, args);
    }
}
