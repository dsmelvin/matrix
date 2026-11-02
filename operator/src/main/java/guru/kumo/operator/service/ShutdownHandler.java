package guru.kumo.operator.service;

import guru.kumo.operator.channel.Channel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("operator")
public class ShutdownHandler {
    List<Channel> channelList;
    private final ChatMemoryService chatMemoryService;
    private final AgentOperator agentOperator;
    private final InterruptController interruptController;

    ShutdownHandler(List<Channel> channelList, ChatMemoryService chatMemoryService, AgentOperator agentOperator,
                    InterruptController interruptController) {
        this.interruptController = interruptController;
        this.channelList = channelList;
        this.chatMemoryService = chatMemoryService;
        this.agentOperator = agentOperator;
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Performing Spring-managed cleanup ...");
        // Stop the running console turn first, so that the chat memory saved below is consistent.
        interruptController.beginShutdown();
        channelList.forEach(Channel::shutdown);
        chatMemoryService.shutdown();
        agentOperator.shutdown();
    }
}