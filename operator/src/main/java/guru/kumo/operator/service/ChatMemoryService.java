package guru.kumo.operator.service;

import guru.kumo.operator.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.session.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@Profile("operator")
public class ChatMemoryService {
    private static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();
    private final SessionService sessionService;
    private final String sessionMemoryPathName;
    private final ConcurrentHashMap<String, Session> concurrentHashMap = new ConcurrentHashMap<>();

    ChatMemoryService(@Value("${agent.path.memory}") String sessionMemoryPathName) {
        this.sessionMemoryPathName = sessionMemoryPathName;
        sessionService = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .defaultTimeToLive(Duration.ofDays(7))
                .allowSystemMessages(true)
                .build();
    }

    public void shutdown() {
        saveSessionMemoryFile();
    }

    public void addChatMemory(String conversationId, Generation generation) {
        if (generation != null) {
            addChatMemory(conversationId, generation.getOutput());
        }
    }

    public void addChatMemory(String conversationId, Message message) {
        addChatMemory(conversationId, List.of(message));
    }

    public void addChatMemory(String conversationId, List<Message> messageList) {
        if (!concurrentHashMap.containsKey(conversationId)) {
            concurrentHashMap.put(conversationId, sessionService.create(CreateSessionRequest.builder()
                    .id(conversationId)
                    .timeToLive(Duration.ofDays(7))
                    .metadata(SessionAttributes.PRINCIPAL_NAME, "matrix")
                    .metadata(SessionAttributes.AGENT_NAME, "operator")
                    .build()));
        }
        messageList.forEach(message -> {
            sessionService.appendMessage(conversationId, message);
        });
    }

    public void removeChatMemory(String conversationId) {
        concurrentHashMap.keySet().forEach(sessionService::delete);
    }

    public List<Message> getChatMemory(String conversationId) {
        return sessionService.getActiveMessages(conversationId);
    }

    public String createConversationId() {
        return UUID.randomUUID().toString();
    }

    private void saveSessionMemoryFile() {
        for (String conversationId : concurrentHashMap.keySet()) {
            if (sessionService.getActiveMessages(conversationId).isEmpty()) continue;
            File sessionMemoryFile = createSessionMemoryFile(conversationId);
            if (sessionMemoryFile != null && sessionMemoryFile.exists()) {
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
    }

    private File createSessionMemoryFile(String conversationId) {
        File sessionMemoryFile = null;
        try {
            if (StringUtils.hasLength(sessionMemoryPathName)) {
                File sessionMemoryPath = new File(Utils.getAbsoluteFilePathName(sessionMemoryPathName));
                if (sessionMemoryPath.exists() && sessionMemoryPath.isDirectory()) {
                    LocalDateTime now = LocalDateTime.now();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
                    String timestamp = now.format(formatter);
                    String fileName = sessionMemoryPath.getAbsolutePath() + "/session-memory-" + timestamp + "-" + conversationId + ".json";
                    sessionMemoryFile = new File(fileName);
                    if (sessionMemoryFile.exists() || sessionMemoryFile.createNewFile()) {
                        log.info("Session memory file opened. {}", fileName);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Session memory file could not be created.", e);
        }
        return sessionMemoryFile;
    }
}
