package guru.kumo.operator.configuration;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;

import java.io.File;

@Configuration
public class CustomMcpConfig {
    @Bean
    @Primary
    @ConditionalOnExpression("!'${agent.mcp-servers-configuration:}'.trim().isEmpty() && new java.io.File('${agent.mcp-servers-configuration:}').exists()")
    public McpStdioClientProperties mcpsStdioClientProperties(@Value("${agent.mcp-servers-configuration}") String mcpServersConfiguration) {
        McpStdioClientProperties properties = new McpStdioClientProperties();
        File jsonFile = new File(mcpServersConfiguration);
        properties.setServersConfiguration(new FileSystemResource(jsonFile));
        return properties;
    }
}