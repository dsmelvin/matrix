package guru.kumo.operator.tool;

import guru.kumo.operator.model.SkillProfile;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SkillsTool {
    public static final String TOOL_NAME = "Skill";
    private static final String TOOL_DESCRIPTION_TEMPLATE = """
            Execute a skill within the main conversation
            
            <skills_instructions>
            When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.
            
            How to use skills:
            - Invoke skills using this tool with the skill name only (no arguments)
            - When you invoke a skill, you will see <command-message>The "{name}" skill is loading</command-message>
            - The skill's prompt will expand and provide detailed instructions on how to complete the task
            
            NOTE: Response always starts start with the base directory of the skill execution environment. You can use this to retrieve additional files of call shell commands.
            Skill description follows after the base directory line.
            
            Important:
            - Only use skills listed in <available_skills> below
            - Do not invoke a skill that is already running
            </skills_instructions>
            
            <available_skills>
            %s
            </available_skills>
            """;

    public record SkillsInput(
            @ToolParam(description = "The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"") String command) {
    }

    public static class SkillsFunction implements Function<SkillsInput, String> {
        private final Map<String, SkillProfile> skillsMap;

        public SkillsFunction(Map<String, SkillProfile> skillsMap) {
            this.skillsMap = skillsMap;
        }

        @Override
        public String apply(SkillsInput input) {
            SkillProfile profile = this.skillsMap.get(input.command());
            if (profile != null) {
                return profile.toString();
            } else {
                return "Skill not found: " + input.command();
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, SkillProfile> skillProfileMap;

        public Builder addSkillsResources(Map<String, SkillProfile> skillProfileMap) {
            this.skillProfileMap = skillProfileMap;
            return this;
        }

        public ToolCallback build() {
            Assert.notEmpty(skillProfileMap, "At least one skill must be configured");
            String skillsXml = skillProfileMap.values().stream().map(SkillProfile::toXml).collect(Collectors.joining("\n"));

            return FunctionToolCallback.builder(TOOL_NAME, new SkillsFunction(skillProfileMap))
                    .description(TOOL_DESCRIPTION_TEMPLATE.formatted(skillsXml))
                    .inputType(SkillsInput.class)
                    .build();
        }
    }
}
