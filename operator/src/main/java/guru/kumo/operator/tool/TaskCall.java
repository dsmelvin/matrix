package guru.kumo.operator.tool;

import org.springframework.ai.tool.annotation.ToolParam;

// @formatter:off
public record TaskCall(
	@ToolParam(description = "A short (3-5 word) description of the task") String description,
	@ToolParam(description = "The task for the agent to perform") String prompt,
	@ToolParam(description = "The type of specialized agent to use for this task") String subagent_type,
	@ToolParam(description = "Optional model to use for this agent. If not specified, inherits from parent. Prefer small models for quick, straightforward tasks to minimize cost and latency.", required = false) String model,
	@ToolParam(description = "Optional agent ID to resume from. If provided, the agent will continue from the previous execution transcript.", required = false) String resume,
	@ToolParam(description = "Set to true to run this agent in the background. Use TaskOutput to read the output later.", required = false) Boolean run_in_background ) {
}
// @formatter:on