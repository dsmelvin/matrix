package guru.kumo.operator.configuration;

import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.presence.ClientActivity;
import discord4j.core.object.presence.ClientPresence;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class DiscordConfiguration {
    /**
     * Unconditional and lazy on purpose: in a GraalVM native image {@code @Conditional*} is evaluated at build time,
     * when the Discord token isn't known yet. The client is therefore always defined, but it only logs in once a
     * consumer (see {@code DiscordChannel} and {@code AgentOperator}) asks for it, and they only do so when
     * {@code agent.channel.discord.token} is set at runtime.
     */
    @Bean
    @Lazy
    public GatewayDiscordClient gatewayDiscordClient(@Value("${agent.channel.discord.token}") String apiToken) {
        return DiscordClientBuilder.create(apiToken).build()
                .gateway()
                .setEnabledIntents(IntentSet.nonPrivileged().or(IntentSet.of(Intent.MESSAGE_CONTENT)))
                .setInitialPresence(ignore -> ClientPresence.online(ClientActivity.listening("to /commands")))
                .login()
                .block();
    }
}
