package guru.kumo.operator.channel.discord.commands;

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import guru.kumo.operator.command.OperatorShellCommand;
import guru.kumo.operator.service.AgentOperatorService;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Profile("operator")
public class AskCommand implements SlashCommand {
    private final AgentOperatorService agentOperatorService;

    AskCommand(AgentOperatorService agentOperatorService) {
        this.agentOperatorService = agentOperatorService;
    }

    @Override
    public String getName() {
        return "ask";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        String question = event.getOption("question")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString)
                .get();
        agentOperatorService.processCall("Discord", OperatorShellCommand.conversationId,
                List.of(UserMessage.builder().text(question).build()));
        return event.reply()
                .withEphemeral(true)
                .withContent("checking ...");
    }
}