/*
 * Copyright 2025 - 2025 the original author or authors.
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

import guru.kumo.operator.service.AgentOperatorService;
import org.springaicommunity.agent.common.task.subagent.SubagentDefinition;
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ClaudeSubagentExecutor implements SubagentExecutor {
    private final List<Resource> skillResources;
    private final AgentOperatorService agentOperatorService;

    @Override
    public String getKind() {
        return ClaudeSubagentDefinition.KIND;
    }

    public ClaudeSubagentExecutor(List<Resource> skillResources, AgentOperatorService agentOperatorService) {
        this.skillResources = skillResources;
        this.agentOperatorService = agentOperatorService;
    }

    @Override
    public String execute(TaskCall taskCall, SubagentDefinition subagent) {
        var claudeSubagent = (ClaudeSubagentDefinition) subagent;
        String preloadedSkillsSystemSuffix = "";
        if (!CollectionUtils.isEmpty(claudeSubagent.skills()) && !CollectionUtils.isEmpty(this.skillResources)) {
            List<SkillsTool.Skill> skills = Skills.loadResources(skillResources);
            preloadedSkillsSystemSuffix = "\n" + skills.stream().filter(s -> claudeSubagent.skills().contains(s.name()))
                    .map(skill -> "%s\nBase directory for this skill: %s\n\n%s".formatted(skill.toXml(),
                            skill.basePath(), skill.content())).collect(Collectors.joining("\n\n"));
        }
        SystemMessage systemMessage = SystemMessage.builder().text(claudeSubagent.getContent() + preloadedSkillsSystemSuffix).build();
        UserMessage userMessage = UserMessage.builder().text(taskCall.prompt()).build();
        String conversationId = UUID.randomUUID().toString();
        return agentOperatorService.startSubAgent(conversationId, taskCall, systemMessage, userMessage);
    }
}
