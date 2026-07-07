package guru.kumo.operator.tool;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.MessageReference;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.MessageEditSpec;
import discord4j.discordjson.json.MessageReferenceData;
import discord4j.discordjson.possible.Possible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Slf4j
public class DiscordTool {
    private final GatewayDiscordClient gatewayDiscordClient;

    DiscordTool(GatewayDiscordClient gatewayDiscordClient) {
        this.gatewayDiscordClient = gatewayDiscordClient;
    }

    // @formatter:off
    @Tool(name = "DiscordSendMessage", description = """
            Use when you want to send a new Discord message that does NOT reference or reply to
            any specific prior message (e.g. starting a new topic, posting a status update).
            If you are responding to a specific message, use DiscordReplyMessage instead so the
            reply is visually threaded in Discord.
            """)
    // @formatter:on
    public String discordSendMessage(@ToolParam(description = "The Discord channel id to send to") String channelId,
                                     @ToolParam(description = "The Discord user id being addressed, if any (for your own reference; you must still put <@userId> in the message text yourself if you want an actual mention)") String userId,
                                     @ToolParam(description = "The message content to send") String message) {
        try {
            log.info("[DiscordSendMessage] channelId:{} userId:{} message:{}", channelId, userId, message);
            gatewayDiscordClient.getChannelById(Snowflake.of(channelId))
                    .ofType(MessageChannel.class)
                    .flatMap(channel -> channel.createMessage(message))
                    .block();
            return "Message is sent successfully.";
        } catch (Exception e) {
            log.error("[DiscordSendMessage] failed", e);
            return "Failed to send message: " + e.getMessage();
        }
    }

    // @formatter:off
    @Tool(name = "DiscordEditMessage", description = """
            Use when you want to edit the content of a message that YOU (this bot) previously
            sent — for example, correcting a mistake, updating a status message in place, or
            refreshing content instead of posting a new message. This only works on messages
            authored by this bot; Discord will reject edits to messages sent by other users or
            other bots, so don't use this to try to change someone else's message. If you want
            to respond to someone else's message, use DiscordReplyMessage instead.
            """)
    // @formatter:on
    public String discordEditMessage(@ToolParam(description = "The Discord channel id the message is in") String channelId,
                                     @ToolParam(description = "The Discord message id to edit (must be a message this bot authored)") String messageId,
                                     @ToolParam(description = "The new message content to replace the existing content with") String newContent) {
        try {
            log.info("[DiscordEditMessage] channelId:{} messageId:{} newContent:{}", channelId, messageId, newContent);
            gatewayDiscordClient.getMessageById(Snowflake.of(channelId), Snowflake.of(messageId))
                    .flatMap(m -> m.edit(MessageEditSpec.builder().content(newContent).build()))
                    .block();
            return "Message is edited successfully.";
        } catch (Exception e) {
            log.error("[DiscordEditMessage] failed", e);
            return "Failed to edit message: " + e.getMessage();
        }
    }

    // @formatter:off
    @Tool(name = "DiscordReplyMessage", description = """
            Use when you want to reply to a specific existing Discord message, so your message
            is threaded under it in Discord's UI. Requires the id of the message you're replying
            to (messageId), not just the channel.
            """)
    // @formatter:on
    public String discordReplyMessage(@ToolParam(description = "The Discord channel id the original message is in") String channelId,
                                      @ToolParam(description = "The Discord message id to reply to") String messageId,
                                      @ToolParam(description = "The Discord user id being replied to (for your own reference; put <@userId> in the message text yourself if you want an actual mention)") String userId,
                                      @ToolParam(description = "The message content to send as the reply") String message) {
        try {
            log.info("[DiscordReplyMessage] channelId:{} messageId:{} userId:{} message:{}", channelId, messageId, userId, message);
            MessageReferenceData referenceData = MessageReferenceData.builder()
                    .type(MessageReference.Type.DEFAULT.getValue())
                    .messageId(messageId)
                    .channelId(channelId)
                    .build();
            MessageCreateSpec spec = MessageCreateSpec.builder()
                    .content(message)
                    .messageReference(Possible.of(referenceData))
                    .build();
            gatewayDiscordClient.getChannelById(Snowflake.of(channelId))
                    .ofType(MessageChannel.class)
                    .flatMap(channel -> channel.createMessage(spec))
                    .block();
            return "Message is replied successfully.";
        } catch (Exception e) {
            log.error("[DiscordReplyMessage] failed", e);
            return "Failed to reply message: " + e.getMessage();
        }
    }

    // @formatter:off
    @Tool(name = "DiscordGetSelfInfo", description = """
            Usage:
                Returns this bot's own Discord user id and username.
                ALWAYS call this first, before processing any incoming Discord message and before composing any reply — do not assume you already know your own id from earlier in the conversation, since sessions can be reused across different bot identities.
                You need this id to:
                  (1) check whether an incoming message's author id matches your own, in which case you must not reply to it, and
                  (2) make sure you never include your own id as a mention in a message you send, which can cause the bot to trigger itself in a loop.
                Call this once per incoming event, before any decision about replying.
            Return:
                - id: The bot's own Discord user id (snowflake), used for mention formatting like <@id> and for comparing against message.author.id.
                - name: The bot's display/username on Discord.
            """)
    // @formatter:on
    public String discordGetSelfInfo() {
        User user = gatewayDiscordClient.getSelf().block();
        return String.format("User Id: %s and User Name: %s", user.getId().asString(), user.getUsername());
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