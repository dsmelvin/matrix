package guru.kumo.operator.channel.discord;

import discord4j.common.JacksonResources;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageUpdateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.RestClient;
import discord4j.rest.service.ApplicationService;
import guru.kumo.operator.channel.Channel;
import guru.kumo.operator.channel.discord.commands.SlashCommand;
import guru.kumo.operator.command.OperatorShellCommand;
import guru.kumo.operator.service.AgentOperatorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class DiscordChannel extends Thread implements Channel {
    private User botUser;
    private String channelId;
    private Snowflake snowflakeChannelId;
    private GatewayDiscordClient gatewayDiscordClient;
    private List<SlashCommand> slashCommandList;
    private AgentOperatorService agentOperatorService;

    public DiscordChannel(List<SlashCommand> slashCommandList,
                          AgentOperatorService agentOperatorService,
                          @Autowired(required = false) GatewayDiscordClient gatewayDiscordClient,
                          @Value("${agent.channel.discord.token}") String apiToken,
                          @Value("${agent.channel.discord.channelId}") String channelId) {
        if (gatewayDiscordClient == null) return;
        if (!StringUtils.hasLength(channelId) || !StringUtils.hasLength(apiToken)) return;
        snowflakeChannelId = Snowflake.of(channelId);
        this.agentOperatorService = agentOperatorService;
        this.gatewayDiscordClient = gatewayDiscordClient;
        this.botUser = gatewayDiscordClient.getSelf().block();
        this.channelId = channelId;
        this.slashCommandList = slashCommandList;
        discordCommand(gatewayDiscordClient.getRestClient());
        this.start();
        sendDiscordMessage("\uD83D\uDC40");
    }

    @Override
    public void shutdown() {
        if (StringUtils.hasLength(channelId)) {
            sendDiscordMessage("\uD83D\uDCA4");
            gatewayDiscordClient.logout().block(Duration.ofSeconds(3));
        }
    }

    @Override
    public void run() {
        Mono.when(
                Flux.merge(gatewayDiscordClient.on(MessageCreateEvent.class), gatewayDiscordClient.on(MessageUpdateEvent.class))
                        .concatMap(event -> {
                            Message incomingMessage;
                            if (event instanceof MessageCreateEvent createEvent) {
                                incomingMessage = createEvent.getMessage();
                            } else if (event instanceof MessageUpdateEvent updateEvent) {
                                incomingMessage = updateEvent.getMessage().block();
                            } else {
                                incomingMessage = null;
                            }

                            if (incomingMessage == null) {
                                return Mono.empty();
                            }
                            if (incomingMessage.getAuthor().isPresent() && incomingMessage.getAuthor().get().getId().equals(botUser.getId())) {
                                return Mono.empty();
                            }
                            if (incomingMessage.getUserMentions().stream().noneMatch(userMention -> userMention.getId().equals(botUser.getId()))) {
                                return Mono.empty();
                            }

                            Message finalIncomingMessage = incomingMessage;

                            return incomingMessage.getChannel()
                                    .flatMap(channel -> channel.createMessage("\uD83E\uDD14 Thinking...")
                                            .flatMap(thinkingMessage -> {
                                                Disposable typingHeartbeat = Flux.interval(Duration.ZERO, Duration.ofSeconds(8))
                                                        .flatMap(tick -> channel.type().onErrorResume(e -> Mono.empty()))
                                                        .subscribe();
                                                return Mono.fromCallable(() -> agentOperatorService.processDiscordRequest(
                                                                OperatorShellCommand.conversationId,
                                                                List.of(UserMessage.builder().text(buildPrompt(finalIncomingMessage)).build())))
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .doFinally(signal -> typingHeartbeat.dispose())
                                                        .flatMap(response -> thinkingMessage.delete())
                                                        .onErrorResume(e -> {
                                                            log.error("[DiscordListener] failed to process message id {}", finalIncomingMessage.getId().asString(), e);
                                                            return thinkingMessage.delete()
                                                                    .then(sendDiscordMessage(channel, finalIncomingMessage, "\u26A0\uFE0F Sorry, something went wrong processing that."))
                                                                    .onErrorResume(deleteErr -> Mono.empty());
                                                        });
                                            }));
                        })
                        .onErrorContinue((error, obj) -> log.error("[DiscordListener] event processing error", error)),
                gatewayDiscordClient.on(ChatInputInteractionEvent.class, this::handle)).subscribe();
    }

    private String buildPrompt(Message incomingMessage) {
        boolean authorIsBot = incomingMessage.getAuthor().map(User::isBot).orElse(false);
        String authorRef = incomingMessage.getAuthor()
                .map(a -> "<@" + a.getId().asString() + ">" + (authorIsBot ? " (this author is a bot)" : ""))
                .orElse("unknown");

        return String.format("""
                        You were mentioned in a Discord message. Carry out what it asks.
                        
                        Only send a Discord reply if the request actually calls for user-facing output — an
                        answer, data, generated content, or something the user explicitly asked to see. If the
                        request is an instruction to perform an action (and doesn't ask for a report back), do
                        NOT send any message about it — not "done", not a summary of what you did, not a status
                        update. Silently completing the task is the correct outcome; do not call
                        DiscordSendMessage/DiscordReplyMessage/DiscordEditMessage just to confirm you did
                        something, unless the user explicitly asked to be told when it's done.
                        
                        %s
                        Author: %s
                        Channel Id: %s
                        Message Id: %s
                        Message:
                        %s
                        """,
                authorIsBot
                        ? "This message was sent by another bot. Only reply if it contains a genuine question or request for you; if it's just a status update/acknowledgment, you may choose not to reply."
                        : "",
                authorRef,
                incomingMessage.getChannelId().asString(),
                incomingMessage.getId().asString(),
                incomingMessage.getContent());
    }

    private void discordCommand(RestClient restClient) {
        JacksonResources d4jMapper = JacksonResources.create();
        PathMatchingResourcePatternResolver matcher = new PathMatchingResourcePatternResolver();
        ApplicationService applicationService = restClient.getApplicationService();
        restClient.getApplicationId().subscribe(applicationId -> {
            try {
                List<ApplicationCommandRequest> commands = new ArrayList<>();
                for (Resource resource : matcher.getResources("commands/*.json")) {
                    ApplicationCommandRequest request = d4jMapper.getObjectMapper().readValue(resource.getInputStream(), ApplicationCommandRequest.class);
                    commands.add(request);
                }
                applicationService.bulkOverwriteGlobalApplicationCommand(applicationId, commands)
                        .doOnNext(ignore -> log.debug("Successfully registered Global Commands"))
                        .doOnError(e -> log.error("Failed to register global commands", e))
                        .subscribe();
            } catch (Exception e) {
                log.error("Failed to register global commands. {}", e.getMessage());
            }
        });
    }

    private Mono<Void> handle(ChatInputInteractionEvent event) {
        return Flux.fromIterable(slashCommandList)
                .filter(command -> command.getName().equals(event.getCommandName()))
                .next()
                .flatMap(command -> command.handle(event));
    }

    private Mono<Void> sendDiscordMessage(MessageChannel messageChannel, Message finalIncomingMessage, String response) {
        if (StringUtils.hasLength(response)) {
            gatewayDiscordClient.getChannelById(messageChannel.getRestChannel().getId()).ofType(MessageChannel.class)
                    .flatMap(channel -> channel.createMessage(response))
                    .then().subscribe();
        }
        return Mono.empty();
    }

    private void sendDiscordMessage(String message) {
        if (StringUtils.hasLength(message)) {
            gatewayDiscordClient.getChannelById(snowflakeChannelId).ofType(MessageChannel.class)
                    .flatMap(channel -> channel.createMessage(message))
                    .then().subscribe();
        }
    }
}
