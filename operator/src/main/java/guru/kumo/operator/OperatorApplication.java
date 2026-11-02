package guru.kumo.operator;

import guru.kumo.operator.configuration.NativeImageRuntimeHints;
import guru.kumo.operator.util.EnvFileLoader;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

// The Google image model is provided by GoogleGenAiConfiguration (lazy, not an autowire candidate). The auto-configured
// GoogleGenAiImageModel must be excluded too: it would otherwise be an eager ImageModel candidate, so resolving the
// ImageModel without Google GenAI configured instantiated it and failed with an NPE in GoogleGenAiConfiguration.credentials().
@SpringBootApplication(exclude = {GoogleGenAiImageConnectionAutoConfiguration.class, GoogleGenAiImageAutoConfiguration.class})
@ImportRuntimeHints(NativeImageRuntimeHints.class)
public class OperatorApplication {
    private static final String DEFAULT_COMMAND = "operator";

    public static void main(String[] args) {
        // Handles --env-file / --workspace; must run before any bean reads the environment.
        EnvFileLoader.prepare(args);
        SpringApplication application = new SpringApplication(OperatorApplication.class);
        application.addInitializers(context -> EnvFileLoader.registerWith(context.getEnvironment()));
        application.run(withDefaultCommand(args));
    }

    /**
     * Runs the "operator" command unless another command was named explicitly, so
     * {@code run -i "hi"} behaves like {@code run operator -i "hi"}. "help ..." still shows the help.
     */
    static String[] withDefaultCommand(String[] args) {
        if (args.length > 0 && (args[0].equals(DEFAULT_COMMAND) || args[0].equals("help"))) {
            return args;
        }
        String[] result = new String[args.length + 1];
        result[0] = DEFAULT_COMMAND;
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }
}
