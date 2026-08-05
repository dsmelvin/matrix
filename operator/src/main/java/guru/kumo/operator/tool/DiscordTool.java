package guru.kumo.operator.tool;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Slf4j
public class DiscordTool {
    public static final String name = "DiscordTool";
    private final GatewayDiscordClient gatewayDiscordClient;

    DiscordTool(GatewayDiscordClient gatewayDiscordClient) {
        this.gatewayDiscordClient = gatewayDiscordClient;
    }

    // @formatter:off
    @Tool(name = name, description = """
            Use when you want to initiate a new conversation and must NOT to response or reply back to someone.
            """)
    // @formatter:on
    public String sendMessage(@ToolParam(description = "The Discord channel id to send to") String channelId,
                              @ToolParam(description = "The Discord user id to send to") String userId,
                              @ToolParam(description = "The message to send to") String message) {
        try {
            log.info("[DiscordTool] channelId:{} userId:{} message:{}", channelId, userId, message);
            gatewayDiscordClient.getRestClient().getChannelById(Snowflake.of(channelId)).createMessage(message).block();
            return "Message to " + userId + " is sent successfully.";
        } catch (Exception e) {
            log.error(e.getMessage());
            return e.getMessage();
        }
    }

    // @formatter:off
    @Tool(name = name, description = """
            Use when you need to know your profile on Discord.
            """)
    // @formatter:on
    public User getUserProfile() {
        return gatewayDiscordClient.getSelf().block();
    }

    public static DiscordTool.Builder builder() {
        return new DiscordTool.Builder();
    }

    public static class Builder {
        private GatewayDiscordClient gatewayDiscordClient;

        private Builder() {
        }

        public DiscordTool.Builder gatewayDiscordClient(GatewayDiscordClient gatewayDiscordClient) {
            this.gatewayDiscordClient = gatewayDiscordClient;
            return this;
        }

        public DiscordTool build() {
            return new DiscordTool(gatewayDiscordClient);
        }
    }
}
