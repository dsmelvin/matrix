package guru.kumo.operator.configuration;

import guru.kumo.operator.model.AgentProfile;
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
public class AgentProfilesConfiguration {
    private final Map<String, AgentProfile> agentProfileMap;

    AgentProfilesConfiguration(@Value("${agent.paths.agents}") List<String> agentPaths) {
        this.agentProfileMap = loadAgents(agentPaths);
    }

    private Map<String, AgentProfile> loadAgents(List<String> agentPaths) {
        Map<String, AgentProfile> agentProfileMap = new HashMap<>();
        HashSet<String> visitedAgents = new HashSet<>();
        if (agentPaths == null || agentPaths.isEmpty()) return agentProfileMap;
        agentPaths.forEach(agentPath -> {
            String agentAbsolutePath = Utils.getAbsolutePath(agentPath);
            if (visitedAgents.contains(agentAbsolutePath)) return;
            visitedAgents.add(agentAbsolutePath);
            try (Stream<Path> pathStream = Files.walk(Path.of("").toAbsolutePath().resolve(agentPath))) {
                pathStream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .forEach(path -> ProfileUtil.buildAgentProfile(path).ifPresent(profile -> agentProfileMap.put(profile.getName(), profile)));
            } catch (IOException ex) {
                log.error("Failed to load Agent from {}", agentPath);
            }
        });
        log.info("Available Agents ({}): {}", agentProfileMap.size(), agentProfileMap.values().stream().map(AgentProfile::getName).collect(Collectors.joining(",")));
        return agentProfileMap;
    }
}
