package guru.kumo.operator.tool.task.claude;

import guru.kumo.operator.service.AgentOperatorService;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentResolver;
import org.springframework.core.io.Resource;

import java.util.List;

public class ClaudeSubagentType {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Resource> skillResources;
        private AgentOperatorService agentOperatorService;

        public Builder skillResources(List<Resource> skillResources) {
            this.skillResources = skillResources;
            return this;
        }

        public Builder operatorService(AgentOperatorService agentOperatorService) {
            this.agentOperatorService = agentOperatorService;
            return this;
        }

        public SubagentType build() {
            ClaudeSubagentExecutor executor = new ClaudeSubagentExecutor(skillResources, agentOperatorService);
            return new SubagentType(new ClaudeSubagentResolver(), executor);
        }
    }
}
