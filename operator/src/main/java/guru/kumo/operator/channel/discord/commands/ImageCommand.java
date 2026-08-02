package guru.kumo.operator.channel.discord.commands;

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.spec.MessageCreateFields;
import guru.kumo.operator.service.AgentOperatorService;
import org.springframework.ai.image.Image;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

@Component
@Profile("operator")
public class ImageCommand implements SlashCommand {
    private final AgentOperatorService agentOperatorService;

    ImageCommand(AgentOperatorService agentOperatorService) {
        this.agentOperatorService = agentOperatorService;
    }

    @Override
    public String getName() {
        return "image";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        String question = event.getOption("description")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString)
                .orElse(null);

        if (question == null) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("Please provide a description.");
        }

        // Defer immediately so Discord doesn't time out (must ack within 3s)
        return event.deferReply().then(Mono.defer(() ->
                // Run the slow, blocking model call off the event loop
                Mono.fromCallable(() -> agentOperatorService.callImageModel(question))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(imageResponse -> {
                            if (imageResponse == null) {
                                return event.editReply("No image model found!").then();
                            }
                            try {
                                Image image = imageResponse.getResult().getOutput();
                                String base64Json = image.getB64Json();
                                if (base64Json == null) {
                                    return event.editReply("Image model did not return image data.").then();
                                }
                                byte[] imageBytes = Base64.getDecoder().decode(base64Json);
                                InputStream inputStream = new ByteArrayInputStream(imageBytes);
                                MessageCreateFields.File fileObject = MessageCreateFields.File.of("image.png", inputStream);
                                return event.editReply(question).withFiles(fileObject).then();
                            } catch (Exception e) {
                                return event.editReply("Failed to load image.").then();
                            }
                        })
                        .onErrorResume(e -> event.editReply("Failed to generate image: " + e.getMessage()).then())
        ));
    }
}