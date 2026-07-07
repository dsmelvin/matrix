package guru.kumo.operator.tool;

import guru.kumo.operator.model.AgentProfile;
import guru.kumo.operator.model.SkillProfile;
import guru.kumo.operator.service.AgentOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TaskTool {
    public static final String TOOL_NAME = "Task";
    private static final String TASK_DESCRIPTION_TEMPLATE = """
            Launch a new agent to handle complex, multi-step tasks autonomously.
            
            The Task tool launches specialized agents (subprocesses) that autonomously handle complex tasks. Each agent type has specific capabilities and tools available to it.
            
            Available agent types and the tools they have access to:
            %s
            
            When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.
            
            When NOT to use the Task tool:
            - If you want to read a specific file path, use the Read or Glob tool instead of the Task tool, to find the match more quickly
            - If you are searching for a specific class definition like "class Foo", use the Glob tool instead, to find the match more quickly
            - If you are searching for code within a specific file or set of 2-3 files, use the Read tool instead of the Task tool, to find the match more quickly
            - Other tasks that are not related to the agent descriptions above
            
            
            Usage notes:
            - Always include a short description (3-5 words) summarizing what the agent will do
            - Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses
            - When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result.
            - You can optionally run agents in the background using the run_in_background parameter. When an agent runs in the background, you will need to use TaskOutput to retrieve its results once it's done. You can continue to work while background agents run - When you need their results to continue you can use TaskOutput in blocking mode to pause and wait for their results.
            - When running tasks in the background, the Task tool will return a task_id immediately. Use the TaskOutput tool with this task_id to check status and retrieve results.
            - Agents can be resumed using the `resume` parameter by passing the agent ID from a previous invocation. When resumed, the agent continues with its full previous context preserved. When NOT resuming, each invocation starts fresh and you should provide a detailed task description with all necessary context.
            - When the agent is done, it will return a single message back to you along with its agent ID. You can use this ID to resume the agent later if needed for follow-up work.
            - Provide clear, detailed prompts so the agent can work autonomously and return exactly the information you need.
            - Agents with "access to current context" can see the full conversation history before the tool call. When using these agents, you can write concise prompts that reference earlier context (e.g., "investigate the error discussed above") instead of repeating information. The agent will receive all prior messages and understand the context.
            - The agent's outputs should generally be trusted
            - Clearly tell the agent whether you expect it to write code or just to do research (search, file reads, web fetches, etc.), since it is not aware of the user's intent
            - If the agent description mentions that it should be used proactively, then you should try your best to use it without the user having to ask for it first. Use your judgement.
            - If the user specifies that they want you to run agents "in parallel", you MUST send a single message with multiple Task tool use content blocks. For example, if you need to launch both a code-reviewer agent and a test-runner agent in parallel, send a single message with both tool calls.
            
            Example usage:
            
            <example_agent_descriptions>
            "code-reviewer": use this agent after you are done writing a signficant piece of code
            "greeting-responder": use this agent when to respond to user greetings with a friendly joke
            </example_agent_description>
            
            <example>
            user: "Please write a function that checks if a number is prime"
            assistant: Sure let me write a function that checks if a number is prime
            assistant: First let me use the Write tool to write a function that checks if a number is prime
            assistant: I'm going to use the Write tool to write the following code:
            <code>
            function isPrime(n) {
            if (n <= 1) return false
            for (let i = 2; i * i <= n; i++) {
            	if (n %% i === 0) return false
            }
            return true
            }
            </code>
            <commentary>
            Since a signficant piece of code was written and the task was completed, now use the code-reviewer agent to review the code
            </commentary>
            assistant: Now let me use the code-reviewer agent to review the code
            assistant: Uses the Task tool to launch the code-reviewer agent
            </example>
            
            <example>
            user: "Hello"
            <commentary>
            Since the user is greeting, use the greeting-responder agent to respond with a friendly joke
            </commentary>
            assistant: "I'm going to use the Task tool to launch the greeting-responder agent"
            </example>
            """;

    @Slf4j
    public static class TaskFunction implements Function<TaskCall, String> {
        private final TaskRepository taskRepository;
        private final AgentOperator agentOperator;

        private Map<String, AgentProfile> agentProfileMap;
        private Map<String, SkillProfile> skillProfileMap;

        public TaskFunction(AgentOperator agentOperator, Map<String, AgentProfile> agentProfileMap, Map<String, SkillProfile> skillProfileMap, TaskRepository taskRepository) {
            this.agentProfileMap = agentProfileMap;
            this.skillProfileMap = skillProfileMap;
            this.taskRepository = taskRepository;
            this.agentOperator = agentOperator;
        }

        @Override
        public String apply(TaskCall taskCall) {
            String subagentName = taskCall.subagent_type();

            if (!agentProfileMap.containsKey(subagentName)) {
                throw new RuntimeException("No subagent found with name: " + subagentName);
            }

            AgentProfile agentProfile = agentProfileMap.get(subagentName);

            if (agentProfile == null) {
                throw new RuntimeException("No subagent executor found for subagent kind: " + subagentName);
            }

            if (Boolean.TRUE.equals(taskCall.run_in_background())) {
                var bgTask = this.taskRepository.putTask("task_" + UUID.randomUUID(), () -> execute(taskCall, agentProfile));
                return String.format("task_id: %s\n\nBackground task started with ID: %s\nUse TaskOutput tool with task_id='%s' to retrieve results.",
                        bgTask.getTaskId(), bgTask.getTaskId(), bgTask.getTaskId());
            }

            // Synchronous execution (existing behavior)
            return execute(taskCall, agentProfile);
        }

        private String execute(TaskCall taskCall, AgentProfile agentProfile) {
            try {
                return executeSubagentExecutor(taskCall, agentProfile);
            } catch (Exception ex) {
                log.error("Subagent '{}' execution failed", agentProfile.getName(), ex);
                throw ex;
            }
        }

        private String executeSubagentExecutor(TaskCall taskCall, AgentProfile agentProfile) {
            String preloadedSkillsSystemSuffix = "";

            if (!CollectionUtils.isEmpty(agentProfile.getSkills()) && !CollectionUtils.isEmpty(skillProfileMap)) {
                preloadedSkillsSystemSuffix = "\n" + skillProfileMap.values().stream().filter(s -> agentProfile.getSkills().contains(s.getName()))
                        .map(skill -> "%s\nBase directory for this skill: %s\n\n%s".formatted(skill.toXml(),
                                skill.getBaseDirectory(), skill.getContent())).collect(Collectors.joining("\n\n"));
            }

            SystemMessage systemMessage = SystemMessage.builder().text(agentProfile.getContent() + preloadedSkillsSystemSuffix).build();
            UserMessage userMessage = UserMessage.builder().text(taskCall.prompt()).build();
            String conversationId = String.format("%s-%s", taskCall.subagent_type(), UUID.randomUUID().toString().replace("-", ""));
            return agentOperator.processSubAgentRequest(conversationId, taskCall, systemMessage, userMessage);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, AgentProfile> agentProfileMap;
        private Map<String, SkillProfile> skillProfileMap;
        private TaskRepository taskRepository;
        private AgentOperator agentOperator;

        public Builder addAgentOperator(AgentOperator agentOperator) {
            this.agentOperator = agentOperator;
            return this;
        }

        public Builder addAgentProfiles(Map<String, AgentProfile> agentProfileMap) {
            this.agentProfileMap = agentProfileMap;
            return this;
        }

        public Builder addSkillProfiles(Map<String, SkillProfile> skillProfileMap) {
            this.skillProfileMap = skillProfileMap;
            return this;
        }

        public Builder taskRepository(TaskRepository taskRepository) {
            Assert.notNull(taskRepository, "taskRepository must not be null");

            this.taskRepository = taskRepository;
            return this;
        }

        public ToolCallback build() {
            Assert.notNull(this.taskRepository, "taskRepository must be provided");

            String subagentRegistrations = agentProfileMap.values().stream()
                    .map(AgentProfile::toSubagentRegistrations)
                    .collect(Collectors.joining("\n"));

            return FunctionToolCallback
                    .builder(TOOL_NAME, new TaskFunction(agentOperator, agentProfileMap, skillProfileMap, taskRepository))
                    .description(TASK_DESCRIPTION_TEMPLATE.formatted(subagentRegistrations))
                    .inputType(TaskCall.class)
                    .build();
        }
    }
}
