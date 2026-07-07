package guru.kumo.operator.service;

import guru.kumo.operator.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class ChatMemoryService {
    private static final ChatMessageListCodec chatMessageListCodec = new ChatMessageListCodec();
    private static final InMemoryChatMemoryRepository inMemoryChatMemoryRepository = new InMemoryChatMemoryRepository();
    private final ChatMemory chatMemory;
    private final String sessionMemoryPathName;

    ChatMemoryService(
            @Value("${agent.path.memory}") String sessionMemoryPathName,
            @Value("${agent.message-window-chat-memory.max-messages}") Integer maxChatMemoryMessages) {
        this.sessionMemoryPathName = sessionMemoryPathName;
        this.chatMemory = MessageWindowChatMemory.builder().chatMemoryRepository(inMemoryChatMemoryRepository).maxMessages(maxChatMemoryMessages).build();
    }

    public void shutdown() {
        saveSessionMemoryFile();
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

    private void saveSessionMemoryFile() {
        for (String conversationId : inMemoryChatMemoryRepository.findConversationIds()) {
            if (chatMemory.get(conversationId).isEmpty()) continue;
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
