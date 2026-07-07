package guru.kumo.operator.configuration;

import guru.kumo.operator.model.SkillProfile;
import guru.kumo.operator.util.ProfileUtil;
import guru.kumo.operator.util.Utils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@Configuration
@Profile("operator")
public class SkillProfilesConfiguration {
    private final Map<String, SkillProfile> skillProfileMap;

    SkillProfilesConfiguration(@Value("${agent.paths.skills}") List<String> skillPaths) {
        this.skillProfileMap = loadSkills(skillPaths);
    }

    private Map<String, SkillProfile> loadSkills(List<String> skillPaths) {
        Map<String, SkillProfile> skillProfileMap = new HashMap<>();
        HashSet<String> visitedSkills = new HashSet<>();
        if (skillPaths == null || skillPaths.isEmpty()) return skillProfileMap;
        skillPaths.forEach(skillPath -> {
            String skillAbsolutePath = Utils.getAbsolutePath(skillPath);
            if (visitedSkills.contains(skillAbsolutePath)) return;
            visitedSkills.add(skillAbsolutePath);
            try (Stream<Path> pathStream = Files.walk(Path.of("").toAbsolutePath().resolve(skillPath))) {
                pathStream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(SkillProfile.SKILL_FILE_NAME))
                        .forEach(path -> ProfileUtil.buildSkillProfile(path).ifPresent(profile -> skillProfileMap.put(profile.getName(), profile)));
            } catch (IOException ex) {
                log.error("Failed to load Skill from {}", skillPath);
            }
        });
        log.info("Available Skills ({}): {}", skillProfileMap.size(), skillProfileMap.values().stream().map(SkillProfile::getName).collect(Collectors.joining(",")));
        return skillProfileMap;
    }
}
