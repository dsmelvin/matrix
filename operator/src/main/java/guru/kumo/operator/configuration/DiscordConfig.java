package guru.kumo.operator.configuration;

import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.presence.ClientActivity;
import discord4j.core.object.presence.ClientPresence;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration
public class DiscordConfig implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String propertyValue = context.getEnvironment().getProperty("agent.channel.discord.token");
        return propertyValue != null && !propertyValue.trim().isEmpty();
    }

    @Bean
    @Conditional(DiscordConfig.class)
    public GatewayDiscordClient gatewayDiscordClient(@Value("${agent.channel.discord.token}") String apiToken) {
        return DiscordClientBuilder.create(apiToken).build()
                .gateway()
                .setEnabledIntents(IntentSet.nonPrivileged().or(IntentSet.of(Intent.MESSAGE_CONTENT)))
                .setInitialPresence(ignore -> ClientPresence.online(ClientActivity.listening("to /commands")))
                .login()
                .block();
    }
}
