/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.agent.tools.task.claude;

import guru.kumo.operator.service.OperatorService;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springframework.core.io.Resource;

import java.util.List;

public class ClaudeSubagentType {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Integer maxChatMemoryMessages;
        private List<Resource> skillResources;
        private OperatorService operatorService;

        public Builder maxChatMemoryMessages(Integer maxChatMemoryMessages) {
            this.maxChatMemoryMessages = maxChatMemoryMessages;
            return this;
        }

        public Builder skillResources(List<Resource> skillResources) {
            this.skillResources = skillResources;
            return this;
        }

        public Builder operatorService(OperatorService operatorService) {
            this.operatorService = operatorService;
            return this;
        }

        public SubagentType build() {
            ClaudeSubagentExecutor executor = new ClaudeSubagentExecutor(skillResources, operatorService, maxChatMemoryMessages);
            return new SubagentType(new ClaudeSubagentResolver(), executor);
        }
    }
}
