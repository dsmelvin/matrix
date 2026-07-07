package guru.kumo.operator.util;

import guru.kumo.operator.model.AgentProfile;
import guru.kumo.operator.model.SkillProfile;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.dataformat.yaml.JacksonYAMLParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
public class ProfileUtil {
    public static Optional<AgentProfile> buildAgentProfile(Path agentProfileFilePath) {
        try {
            String[] profile = buildProfile(agentProfileFilePath);
            if (profile.length == 2) {
                return Optional.of(new AgentProfile(agentProfileFilePath.toAbsolutePath().toFile(), profile[0], profile[1]));
            }
        } catch (IOException e) {
            log.error("Failed to read Agent profile from {}", agentProfileFilePath);
        } catch (JacksonYAMLParseException e) {
            log.error("Invalid frontmatter format from {}", agentProfileFilePath);
        }
        return Optional.empty();
    }

    public static Optional<SkillProfile> buildSkillProfile(Path skillProfileFilePath) {
        try {
            String[] profile = buildProfile(skillProfileFilePath);
            if (profile.length == 2) {
                return Optional.of(new SkillProfile(skillProfileFilePath.toAbsolutePath().getParent(), profile[0], profile[1]));
            }
        } catch (IOException e) {
            log.error("Failed to read Skill profile from {}", skillProfileFilePath);
        } catch (JacksonYAMLParseException e) {
            log.error("Invalid frontmatter format from {}", skillProfileFilePath);
        }
        return Optional.empty();
    }

    private static String[] buildProfile(Path skillProfileFilePath) throws IOException {
        String fileContent = Files.readString(skillProfileFilePath, StandardCharsets.UTF_8);
        int firstDelimiter = fileContent.indexOf("---");
        int secondDelimiter = fileContent.indexOf("---", firstDelimiter + "---".length());
        if (secondDelimiter > firstDelimiter) {
            String yamlFrontmatter = fileContent.substring(firstDelimiter + "---".length(), secondDelimiter).trim();
            String payload = fileContent.substring(secondDelimiter + "---".length()).trim();
            return new String[]{yamlFrontmatter, payload};
        } else {
            return new String[0];
        }
    }
}
