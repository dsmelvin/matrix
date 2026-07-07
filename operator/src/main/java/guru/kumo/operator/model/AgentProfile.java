package guru.kumo.operator.model;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.util.*;

@Slf4j
@Getter
public class AgentProfile {
    private static final String FRONTMATTER_NAME_KEY = "name";
    private static final String FRONTMATTER_DESCRIPTION_KEY = "description";
    private static final String FRONTMATTER_TOOLS_KEY = "tools";
    private static final String FRONTMATTER_DISALLOWED_TOOLS_KEY = "disallowedTools";
    private static final String FRONTMATTER_SKILLS_KEY = "skills";
    private static final String FRONTMATTER_MODEL_KEY = "model";
    private static final String FRONTMATTER_PERMISSION_MODE_KEY = "permissionMode";
    private static final String FRONTMATTER_MCP_SERVERS_KEY = "mcpServers";

    private final File agentFile;
    private final String yamlFrontmatter;
    private final String content;
    private final Map<String, Object> frontMatter;
    private final String name;
    private final String description;
    private final Set<String> skills = new HashSet<>();
    private final Set<String> tools = new HashSet<>();
    private final Set<String> disallowedTools = new HashSet<>();

    public AgentProfile(File agentFile, String yamlFrontmatter, String content) {
        this.agentFile = agentFile;
        this.yamlFrontmatter = yamlFrontmatter;
        this.content = content;
        YAMLMapper yamlMapper = YAMLMapper.builder().build();
        frontMatter = yamlMapper.readValue(yamlFrontmatter, HashMap.class);
        name = frontMatter.get(FRONTMATTER_NAME_KEY).toString();
        description = frontMatter.get(FRONTMATTER_DESCRIPTION_KEY).toString();
        if (frontMatter.containsKey(FRONTMATTER_SKILLS_KEY)) {
            skills.addAll((List<String>) frontMatter.get(FRONTMATTER_SKILLS_KEY));
        }
        if (frontMatter.containsKey(FRONTMATTER_TOOLS_KEY)) {
            tools.addAll(Arrays.stream(frontMatter.get(FRONTMATTER_TOOLS_KEY).toString().split(",")).map(String::trim).toList());
        }
        if (frontMatter.containsKey(FRONTMATTER_DISALLOWED_TOOLS_KEY)) {
            disallowedTools.addAll(Arrays.stream(frontMatter.get(FRONTMATTER_DISALLOWED_TOOLS_KEY).toString().split(",")).map(String::trim).toList());
        }
    }

    public String toSubagentRegistrations() {
        return "- " + name + ": " + description;
    }

    public String toString() {

        return "Agent file for this agent profile: %s".formatted(agentFile);
    }
}
