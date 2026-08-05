package guru.kumo.operator.command;

import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.service.ChatMessageListCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@Profile("operator")
public class OperatorShellCommand {
    public static final String conversationId = UUID.randomUUID().toString();

    private static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();

    private final String sessionMemoryPathName;
    private final AgentOperatorService agentOperatorService;

    OperatorShellCommand(AgentOperatorService agentOperatorService, @Value("${agent.path.memory}") String sessionMemoryPathName) {
        this.sessionMemoryPathName = sessionMemoryPathName;
        this.agentOperatorService = agentOperatorService;
    }

    @Command(name = {"operator"})
    public void run(
            @Option(longName = "system-prompt-file", shortName = 's', required = false, description = "To preload a system prompt file")
            String systemPromptFileName,
            @Option(longName = "user-prompt-file", shortName = 'u', required = false, description = "To preload a user prompt file")
            String userPromptFileName,
            @Option(longName = "session-memory-file", shortName = 'm', required = false, description = "The history of chat memory file")
            String savedSessionMemoryFileName) {
        ArrayList<Message> messageArrayList = new ArrayList<>();
        Optional.ofNullable(loadSystemPrompt(systemPromptFileName)).ifPresent(messageArrayList::add);
        Optional.ofNullable(loadSavedSessionMemoryFile(savedSessionMemoryFileName)).ifPresent(messageArrayList::addAll);
        Optional.ofNullable(loadPromptFile(userPromptFileName)).ifPresent(messageArrayList::add);
        agentOperatorService.processConsoleInitMessage(conversationId, messageArrayList);
    }

    private List<Message> loadSavedSessionMemoryFile(String savedSessionMemoryFileName) {
        try {
            if (savedSessionMemoryFileName == null) return null;
            File savedSessionMemoryFile = new File(savedSessionMemoryFileName);
            if (savedSessionMemoryFile.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(savedSessionMemoryFile)) {
                    List<Message> messageList = chatMessageListCodec.deserialize(StreamUtils.copyToString(fileInputStream, StandardCharsets.UTF_8));
                    log.info("Session memory loaded successfully.");
                    return messageList;
                }
            } else {
                log.error("Session memory doesn't exist. {}", savedSessionMemoryFileName);
                return null;
            }
        } catch (IOException e) {
            log.error("Error reading session memory file", e);
            throw new RuntimeException(e);
        }
    }

    private SystemMessage loadSystemPrompt(String systemPromptFileName) {
        try {
            if (systemPromptFileName == null) return null;
            FileSystemResource operatorSystemPrompt = new FileSystemResource(systemPromptFileName);
            if (operatorSystemPrompt.exists() && operatorSystemPrompt.isFile()) {
                String workingDirectory = System.getProperty("user.dir");
                String platform = System.getProperty("os.name").toLowerCase();
                String osVersion = System.getProperty("os.name") + " " + System.getProperty("os.version");
                String todayDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

                HashMap<String, Object> systemPromptEnvMap = new HashMap<>();
                systemPromptEnvMap.put("WorkingDirectory", workingDirectory);
                systemPromptEnvMap.put("Platform", platform);
                systemPromptEnvMap.put("OSVersion", osVersion);
                systemPromptEnvMap.put("Today", todayDate);
                systemPromptEnvMap.put("MEMORIES_ROOT_DIERCTORY", sessionMemoryPathName == null ? "NONE" : sessionMemoryPathName);
                systemPromptEnvMap.put("OSShell", System.getenv("SHELL") == null ? "UNKNOWN" : System.getenv("SHELL"));

                PromptTemplate systemTemplate = new PromptTemplate(operatorSystemPrompt);
                log.info("System Message Environment Variables: {}", systemPromptEnvMap);
                SystemMessage systemMessage = SystemMessage.builder().text(systemTemplate.render(systemPromptEnvMap)).build();
                log.info("System Message loaded successfully");
                return systemMessage;
            } else {
                log.error("System prompt file doesn't exist. {}", systemPromptFileName);
                return null;
            }
        } catch (Exception e) {
            log.error("Error reading system prompt file", e);
            throw new RuntimeException(e);
        }
    }

    private UserMessage loadPromptFile(String userPromptFileName) {
        try {
            if (userPromptFileName == null) return null;
            File promptFile = new File(userPromptFileName);
            if (promptFile.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(promptFile)) {
                    log.info("Prompt file loaded successfully. {}", promptFile.getAbsolutePath());
                    return UserMessage.builder().text(StreamUtils.copyToString(fileInputStream, StandardCharsets.UTF_8)).build();
                }
            } else {
                log.error("User prompt file doesn't exist. {}", userPromptFileName);
                return null;
            }
        } catch (IOException e) {
            log.error("Error reading user prompt file", e);
            throw new RuntimeException(e);
        }
    }
}
