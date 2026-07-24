package guru.kumo.operator.service;

import guru.kumo.operator.util.ColorEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class ChatMemoryService {
    private static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();

    private final ChatMemory chatMemory;
    private final String sessionMemoryPathName;
    private final Resource operatorSystemPrompt;

    ChatMemoryService(
            @Value("${agent.prompt.system}") String operatorSystemPrompt,
            @Value("${agent.path.memory}") String sessionMemoryPathName,
            @Value("${agent.message-window-chat-memory.max-messages}") Integer maxChatMemoryMessages) {
        this.operatorSystemPrompt = new FileSystemResource(operatorSystemPrompt);
        this.sessionMemoryPathName = sessionMemoryPathName;
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(maxChatMemoryMessages).build();
    }

    public void shutdown(String conversationId, boolean saveSessionMemory) {
        saveSessionMemoryFile(conversationId, saveSessionMemory);
    }

    public void addChatMemory(String conversationId, Message message) {
        addChatMemory(conversationId, List.of(message));
    }

    public void addChatMemory(String conversationId, List<Message> messageList) {
        chatMemory.add(conversationId, messageList);
    }

    public List<Message> getChatMemory(String conversationId) {
        return chatMemory.get(conversationId);
    }

    public SystemMessage loadSystemPrompt(String conversationId) {
        if (operatorSystemPrompt != null && operatorSystemPrompt.exists() && operatorSystemPrompt.isFile()) {
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
            addChatMemory(conversationId, systemMessage);
            log.info("System Message loaded successfully");
            System.out.printf("%s[PREFILL][SYSTEM]:[%n%s%n]%s%n%n", ColorEnum.ORANGE, systemMessage.getText(), ColorEnum.RESET);
            return systemMessage;
        }
        return null;
    }

    public void loadSavedSessionMemoryFile(String conversationId, String savedSessionMemoryFileName) {
        try {
            if (savedSessionMemoryFileName == null) return;
            File savedSessionMemoryFile = new File(savedSessionMemoryFileName);
            if (savedSessionMemoryFile.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(savedSessionMemoryFile)) {
                    List<Message> messageList = chatMessageListCodec.deserialize(StreamUtils.copyToString(fileInputStream, StandardCharsets.UTF_8));
                    addChatMemory(conversationId, messageList);
                    log.info("Session memory loaded successfully.");
                }
            } else {
                log.error("Session memory doesn't exist. {}", savedSessionMemoryFileName);
            }
        } catch (IOException e) {
            log.error("Error reading session memory file", e);
            throw new RuntimeException(e);
        }
    }

    private void saveSessionMemoryFile(String conversationId, boolean saveSessionMemory) {
        File sessionMemoryFile = saveSessionMemory ? createSessionMemoryFile() : null;
        if (saveSessionMemory && sessionMemoryFile != null && sessionMemoryFile.exists()) {
            try (FileOutputStream fileOutputStream = new FileOutputStream(sessionMemoryFile)) {
                StreamUtils.copy(chatMessageListCodec.serialize(getChatMemory(conversationId)).getBytes(), fileOutputStream);
                fileOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
                fileOutputStream.flush();
                log.info("Session memory file saved successfully. {}", sessionMemoryFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("Session memory file could not be saved.", e);
            }
        }
    }

    private File createSessionMemoryFile() {
        File sessionMemoryFile = null;
        try {
            if (StringUtils.hasLength(sessionMemoryPathName)) {
                File sessionMemoryPath = new File(sessionMemoryPathName);
                if (sessionMemoryPath.exists() && sessionMemoryPath.isDirectory()) {
                    LocalDateTime now = LocalDateTime.now();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
                    String timestamp = now.format(formatter);
                    String fileName = sessionMemoryPath.getAbsolutePath() + "/session-memory-" + timestamp + ".json";
                    sessionMemoryFile = new File(fileName);
                    if (sessionMemoryFile.exists() || sessionMemoryFile.createNewFile()) {
                        log.info("Session memory file opened. {}", fileName);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Session memory file could not be created.", e);
            throw new RuntimeException(e);
        }
        return sessionMemoryFile;
    }
}
