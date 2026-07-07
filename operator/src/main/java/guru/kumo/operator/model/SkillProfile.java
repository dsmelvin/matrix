package guru.kumo.operator.model;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class SkillProfile {
    public static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String FRONTMATTER_NAME_KEY = "name";
    private static final String FRONTMATTER_DESCRIPTION_KEY = "description";
    private static final String FRONTMATTER_ALLOWED_TOOLS_KEY = "allowed-tools"; // A space-separated string of tools that are pre-approved to run
    private static final String FRONTMATTER_METADATA_KEY = "metadata";

    private final Path baseDirectory;
    private final String yamlFrontmatter;
    private final String content;
    private final Map<String, Object> frontMatter;
    private final String name;
    private final String description;
    private final List<String> allowedTools = new ArrayList<>();

    public SkillProfile(Path baseDirectory, String yamlFrontmatter, String content) {
        this.baseDirectory = baseDirectory;
        this.yamlFrontmatter = yamlFrontmatter;
        this.content = content;
        YAMLMapper yamlMapper = YAMLMapper.builder().build();
        frontMatter = yamlMapper.readValue(yamlFrontmatter, HashMap.class);
        name = frontMatter.get(FRONTMATTER_NAME_KEY).toString();
        description = frontMatter.get(FRONTMATTER_DESCRIPTION_KEY).toString();
        if (frontMatter.containsKey(FRONTMATTER_ALLOWED_TOOLS_KEY)) {
            allowedTools.addAll(Arrays.stream(frontMatter.get(FRONTMATTER_ALLOWED_TOOLS_KEY).toString().split(" ")).map(String::trim).toList());
        }
    }

    public String toXml() {
        String frontMatterXml = frontMatter
                .entrySet()
                .stream()
                .map(e -> "  <%s>%s</%s>".formatted(e.getKey(), e.getValue(), e.getKey()))
                .collect(Collectors.joining("\n"));
        return "<skill>\n%s\n</skill>".formatted(frontMatterXml);
    }

    @Override
    public String toString() {
        return "Base directory for this skill: %s\n\n%s".formatted(baseDirectory, content);
    }
}
