package guru.kumo.operator.tool.agent.task.claude;

import guru.kumo.operator.service.AgentOperatorService;
import guru.kumo.operator.tool.agent.task.model.SubagentType;
import org.springframework.core.io.Resource;

import java.util.List;

public class ClaudeSubagentTypeUtil {
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
