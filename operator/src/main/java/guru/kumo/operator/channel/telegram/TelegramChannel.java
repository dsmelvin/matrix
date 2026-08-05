package guru.kumo.operator.channel.telegram;

import guru.kumo.operator.channel.Channel;
import guru.kumo.operator.command.OperatorShellCommand;
import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.tool.ImageReaderTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@Profile("operator")
public class TelegramChannel extends DefaultLongPollingUpdateConsumer implements Channel {
    private Long userId;
    private String apiToken;
    private TelegramClient telegramClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private AgentOperatorService agentOperatorService;
    private DefaultLongPollingUpdateConsumer defaultLongPollingUpdateConsumer;

    @Bean
    @ConditionalOnExpression("!'${agent.channel.telegram.userId:}'.trim().isEmpty()")
    public SpringLongPollingBot springLongPollingBot() {
        return new SpringLongPollingBot() {
            @Override
            public String getBotToken() {
                return apiToken;
            }

            @Override
            public LongPollingUpdateConsumer getUpdatesConsumer() {
                return defaultLongPollingUpdateConsumer;
            }
        };
    }

    public TelegramChannel(AgentOperatorService agentOperatorService,
                           ThreadPoolTaskScheduler taskScheduler,
                           @Value("${agent.channel.telegram.userId}") Long userId,
                           @Value("${agent.channel.telegram.token}") String apiToken) {
        if (userId == null || !StringUtils.hasLength(apiToken)) return;
        this.userId = userId;
        this.apiToken = apiToken;
        this.taskScheduler = taskScheduler;
        this.agentOperatorService = agentOperatorService;
        defaultLongPollingUpdateConsumer = this;
        telegramClient = new OkHttpTelegramClient(apiToken);
        sendTelegramMessage("Just connected ...");
    }

    @Override
    public void shutdown() {
        if (StringUtils.hasLength(apiToken)) {
            sendTelegramMessage("Going offline ...");
            close();
        }
    }


    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().getFrom().getId().equals(userId)) return;
        long chat_id = update.getMessage().getChatId();
        if (update.hasMessage() && update.getMessage().hasText()) {
            String message_text = update.getMessage().getText();
            SendMessage.SendMessageBuilder<?, ?> sendMessageBuilder = SendMessage.builder();
            if (message_text.equals("/start")) {
                sendMessageBuilder.chatId(chat_id).text("✅ \uD83D\uDE00 Chat Id: " + chat_id);
                sendTelegramMessage(sendMessageBuilder.build());
            } else {
                sendTelegramAction(SendChatAction.builder().action(ActionType.TYPING.name()).chatId(chat_id).build());
                ScheduledFuture<?> scheduledFuture = showTelegramTyping(chat_id);
                sendTelegramMessage(agentOperatorService.processTelegramRequest(OperatorShellCommand.conversationId, List.of(UserMessage.builder().text(message_text).build())));
                scheduledFuture.cancel(true);
            }
        } else if (update.hasMessage() && update.getMessage().hasPhoto()) {
            List<PhotoSize> photos = update.getMessage().getPhoto();
            Optional<PhotoSize> photoSizeOptional = photos.stream().max(Comparator.comparing(PhotoSize::getFileSize));
            if (photoSizeOptional.isEmpty()) return;
            PhotoSize photo = photoSizeOptional.get();
            String fileId = photo.getFileId();
            int maxWidth = photo.getWidth();
            int maxHeight = photo.getHeight();
            String filePath = photo.getFilePath();
            int fileSize = photo.getFileSize();
            String fileUniqueId = photo.getFileUniqueId();
            sendTelegramPhoto(SendPhoto.builder()
                    .chatId(chat_id)
                    .photo(new InputFile(fileId))
                    .caption(String.format("file id: %s\nfile unique id: %s\nsize: %d\nwidth: %d\nheight: %d\nfile path: %s",
                            fileId, fileUniqueId, fileSize, maxWidth, maxHeight, filePath))
                    .build());
            UserMessage.Builder builder = UserMessage.builder().text(ImageReaderTool.name);
            try {
                InputStreamResource inputStreamResource = new InputStreamResource(Objects.requireNonNull(getTelegramFile(photo.getFileId())));
                MimeType mimeType = MimeTypeUtils.IMAGE_PNG;
                builder.media(new Media(mimeType, inputStreamResource));
            } catch (Exception e) {
                builder.text(e.getMessage()).build();
            }

            ScheduledFuture<?> scheduledFuture = showTelegramTyping(chat_id);
            sendTelegramMessage(agentOperatorService.processTelegramRequest(OperatorShellCommand.conversationId,
                    List.of(builder.text(update.getMessage().hasText() ? update.getMessage().getText() : "ask the user what should we do about it").build())));
            scheduledFuture.cancel(true);
        } else if (update.hasMessage() && update.getMessage().hasDocument()) {
            Document document = update.getMessage().getDocument();
            String fileName = document.getFileName();
            UserMessage.Builder builder = UserMessage.builder().text(ImageReaderTool.name);
            try {
                InputStreamResource inputStreamResource = new InputStreamResource(Objects.requireNonNull(getTelegramFile(document.getFileId())));
                MimeType mimeType = MimeTypeUtils.parseMimeType(Files.probeContentType(Path.of(fileName)));
                if (!mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_JPEG) && !mimeType.equalsTypeAndSubtype(MimeTypeUtils.IMAGE_PNG)) {
                    builder.text("Can't load images other than PNG or JPEG").build();
                }
                builder.media(new Media(mimeType, inputStreamResource));
            } catch (Exception e) {
                builder.text(e.getMessage()).build();
            }
            ScheduledFuture<?> scheduledFuture = showTelegramTyping(chat_id);
            agentOperatorService.processTelegramRequest(OperatorShellCommand.conversationId,
                    List.of(builder.text(update.getMessage().hasCaption() ? update.getMessage().getCaption() : "ask the user what should we do about it").build()));
            scheduledFuture.cancel(true);
        }
    }

    private ScheduledFuture<?> showTelegramTyping(long chatId) {
        Runnable taskAction = () -> sendTelegramAction(SendChatAction.builder().action(ActionType.TYPING.name()).chatId(chatId).build());
        return taskScheduler.scheduleAtFixedRate(taskAction, Duration.ofSeconds(3));
    }

    private InputStream getTelegramFile(String fileId) {
        try {
            return telegramClient.downloadFileAsStream(telegramClient.execute(GetFile.builder().fileId(fileId).build()));
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
            return null;
        }
    }

    private void sendTelegramPhoto(SendPhoto sendPhoto) {
        try {
            telegramClient.execute(sendPhoto);
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }

    private void sendTelegramAction(SendChatAction sendChatAction) {
        try {
            telegramClient.execute(sendChatAction);
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }

    private void sendTelegramMessage(SendMessage sendMessage) {
        try {
            telegramClient.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }

    private void sendTelegramMessage(String message) {
        sendTelegramMessage(SendMessage.builder().chatId(userId).text(message).build());
    }
}
