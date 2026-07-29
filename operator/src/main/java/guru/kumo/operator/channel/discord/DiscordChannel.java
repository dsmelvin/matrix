package guru.kumo.operator.channel.discord;

import discord4j.common.JacksonResources;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.presence.ClientActivity;
import discord4j.core.object.presence.ClientPresence;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import discord4j.rest.RestClient;
import discord4j.rest.service.ApplicationService;
import guru.kumo.operator.channel.Channel;
import guru.kumo.operator.channel.discord.commands.SlashCommand;
import guru.kumo.operator.command.OperatorShellCommand;
import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.tool.TodoWriteTool;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Profile("operator")
public class DiscordChannel implements Runnable, Channel {
    private User botUser;
    private String channelId;
    private Snowflake snowflake;
    private GatewayDiscordClient gatewayDiscordClient;
    private List<SlashCommand> slashCommandList;
    private AgentOperatorService agentOperatorService;

    public DiscordChannel(List<SlashCommand> slashCommandList,
                          AgentOperatorService agentOperatorService,
                          @Value("${agent.channel.discord.token}") String apiToken,
                          @Value("${agent.channel.discord.channelId}") String channelId) {
        if (!StringUtils.hasLength(channelId) || !StringUtils.hasLength(apiToken)) return;
        snowflake = Snowflake.of(channelId);
        this.agentOperatorService = agentOperatorService;
        gatewayDiscordClient = DiscordClientBuilder.create(apiToken).build()
                .gateway()
                .setEnabledIntents(IntentSet.nonPrivileged().or(IntentSet.of(Intent.MESSAGE_CONTENT)))
                .setInitialPresence(ignore -> ClientPresence.online(ClientActivity.listening("to /commands")))
                .login()
                .block();
        if (gatewayDiscordClient != null) {
            this.botUser = gatewayDiscordClient.getSelf().block();
            this.channelId = channelId;
            this.slashCommandList = slashCommandList;
            discordCommand(gatewayDiscordClient.getRestClient());
            start(new Thread(this), agentOperatorService);
            sendDiscordMessage("Just connected ...");
        }
    }

    @Override
    public void run() {
        Mono.when(gatewayDiscordClient.on(MessageCreateEvent.class, event ->
                        Mono.just(event.getMessage())
                                .filter(message -> message.getUserMentions().stream().anyMatch(userMention -> userMention.getId().equals(botUser.getId())))
                                .flatMap(Message::getChannel)
                                .flatMap(channel -> channel.createMessage("checking ...")).doOnNext(message ->
                                        agentOperatorService.processCall("Discord", OperatorShellCommand.conversationId,
                                                List.of(UserMessage.builder().text(event.getMessage().getContent()).build())))),
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
        gatewayDiscordClient.getChannelById(snowflake).ofType(MessageChannel.class)
                .flatMap(channel -> channel.createMessage(message))
                .then().subscribe();
    }

    @Override
    public void prefillOutput(List<org.springframework.ai.chat.messages.Message> messageList) {
        for (org.springframework.ai.chat.messages.Message message : messageList) {
            switch (message.getMessageType()) {
                case SYSTEM -> sendDiscordMessage("System prompt is preloaded.");
                case USER -> sendDiscordMessage("User prompt is preloaded.");
            }
        }
    }

    @Override
    public void agent(String logPrefix, ChatResponse chatResponse) {
        if (chatResponse == null) return;
        if (!StringUtils.hasLength(chatResponse.getResult().getOutput().getText())) return;
        sendDiscordMessage(String.format("[ASSISTANT]: %s", chatResponse.getResult().getOutput().getText()));
    }

    @Override
    public void subagent(TaskCall taskCall, SystemMessage systemMessage, UserMessage userMessage) {
        sendDiscordMessage(String.format("[%s][SYSTEM]: %s", taskCall.subagent_type(), systemMessage.getText()));
        sendDiscordMessage(String.format("[%s][USER]: %s", taskCall.subagent_type(), userMessage.getText()));
    }

    @Override
    public void todos(TodoWriteTool.Todos event) {
        List<TodoWriteTool.Todos.TodoItem> todos = event.todos();
        int completed = (int) todos.stream().filter(t -> t.status() == TodoWriteTool.Todos.Status.completed).count();
        int total = todos.size();

        sendDiscordMessage(String.format("Progress: %d/%d tasks completed (%.0f%%)", completed, total, (completed * 100.0 / total)));

        for (TodoWriteTool.Todos.TodoItem item : todos) {
            String statusIcon = switch (item.status()) {
                case completed -> "[✓]";
                case in_progress -> "[→]";
                case pending -> "[ ]";
            };
            sendDiscordMessage(String.format("%s %s", statusIcon, item.content()));
        }
    }

    @Override
    public void toolCallToString(String logPrefix, AssistantMessage.ToolCall toolCall) {
        // sendDiscordMessage(String.format("TollCall[id=%s, type=%s, name=%s, arguments={%s}]", toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments().substring(0, Math.min(132, toolCall.arguments().length()))));
    }

    @Override
    public void toolResponseToString(String logPrefix, ToolResponseMessage.ToolResponse toolResponse) {
        // sendDiscordMessage(String.format("ToolResponse[id=%s, name=%s, responseData=%s]", toolResponse.id(), toolResponse.name(), toolResponse.responseData().substring(0, Math.min(132, toolResponse.responseData().length()))));
    }

    @Override
    public void shutdown() {
        if (StringUtils.hasLength(channelId)) {
            sendDiscordMessage("Going offline ...");
            gatewayDiscordClient.logout().block();
        }
    }
}
