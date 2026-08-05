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
import discord4j.core.spec.MessageEditSpec;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.discordjson.possible.Possible;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        sendDiscordMessage("Just connected ...");
    }

    @Override
    public void shutdown() {
        if (StringUtils.hasLength(channelId)) {
            sendDiscordMessage("Going offline ...");
            gatewayDiscordClient.logout().block();
        }
    }

    @Override
    public void run() {
        Mono.when(
                Flux.merge(gatewayDiscordClient.on(MessageCreateEvent.class), gatewayDiscordClient.on(MessageUpdateEvent.class)).concatMap(event -> {
                    Message incomingMessage;
                    if (event instanceof MessageCreateEvent createEvent) {
                        incomingMessage = createEvent.getMessage();
                    } else if (event instanceof MessageUpdateEvent updateEvent) {
                        incomingMessage = updateEvent.getMessage().block();
                    } else {
                        incomingMessage = null;
                    }

                    if (incomingMessage.getAuthor().isPresent() && incomingMessage.getAuthor().get().getId().equals(botUser.getId())) {
                        return Mono.empty();
                    }
                    if (incomingMessage.getUserMentions().stream().noneMatch(userMention -> userMention.getId().equals(botUser.getId()))) {
                        return Mono.empty();
                    }
                    return incomingMessage.getChannel()
                            .flatMap(channel -> channel.createMessage("\uD83E\uDD14 Thinking...")
                                    .flatMap(thinkingMessage -> channel.type().then(Mono.fromCallable(() ->
                                                    agentOperatorService.processDiscordRequest(OperatorShellCommand.conversationId,
                                                            List.of(UserMessage.builder().text(String.format("""
                                                                            You've been mentioned in a message id %s from channel id %s %s.
                                                                            Here is the message:
                                                                            %s
                                                                            """,
                                                                    incomingMessage.getId().asString(),
                                                                    incomingMessage.getChannelId().asString(),
                                                                    incomingMessage.getAuthor().isPresent() ? "by user id " + incomingMessage.getAuthor().get().getId().asString() : "",
                                                                    incomingMessage.getContent())).build())))
                                            .subscribeOn(Schedulers.boundedElastic()).flatMap(response -> {
                                                if (StringUtils.hasLength(response)) {
                                                    MessageEditSpec editPayload = MessageEditSpec.builder().content(Possible.of(Optional.of(response))).build();
                                                    return thinkingMessage.edit(editPayload);
                                                }
                                                return Mono.empty();
                                            })
                                    )));
                }),
                gatewayDiscordClient.on(ChatInputInteractionEvent.class, this::handle)).subscribe();
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

    private void sendDiscordMessage(String message) {
        if (StringUtils.hasLength(message)) {
            gatewayDiscordClient.getChannelById(snowflakeChannelId).ofType(MessageChannel.class)
                    .flatMap(channel -> channel.createMessage(message))
                    .then().subscribe();
        }
    }
}
