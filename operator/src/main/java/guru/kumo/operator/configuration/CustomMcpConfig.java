package guru.kumo.operator.configuration;

import guru.kumo.operator.util.Utils;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manually parses an MCP servers configuration file (same {@code mcpServers} JSON shape
 * used by Claude Desktop / Claude Code / Cursor) and builds MCP sync clients for whichever
 * transport each entry declares: stdio (default, via command/args/env), sse, or
 * streamable-http (aliased as "http").
 * <p>
 * Example config file:
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/Desktop"]
 *     },
 *     "remote-tools": {
 *       "type": "streamable-http",
 *       "url": "http://localhost:8080/mcp"
 *     },
 *     "legacy-server": {
 *       "type": "sse",
 *       "url": "http://old-server:8080/sse"
 *     }
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Configuration
@Profile("operator")
public class CustomMcpConfig {
    private final static List<McpSyncClient> mcpSyncClientList = new ArrayList<>();
    private final static JsonMapper jsonMapper = new JsonMapper();

    CustomMcpConfig(@Value("${agent.mcp-servers-configuration}") String mcpServersConfiguration) {
        try {
            FileSystemResource resource = new FileSystemResource(new File(Utils.getAbsoluteFilePathName(mcpServersConfiguration)));
            ClaudeDesktopConfig config = jsonMapper.readValue(resource.getInputStream(), ClaudeDesktopConfig.class);
            for (var entry : config.mcpServers().entrySet()) {
                mcpSyncClientList.add(buildClient(entry.getKey(), entry.getValue()));
            }
            log.info("Total of {} MCP Server(s)  loaded successfully.", mcpSyncClientList.size());
        } catch (Exception e) {
            log.error("Failed to load MCP Server(s): {}\n{}", mcpServersConfiguration, e.getMessage());
        }
    }

    public void shutdown() {
        for (McpSyncClient client : mcpSyncClientList) {
            try {
                client.close();
            } catch (Exception ex) {
                // log and continue closing the rest
            }
        }
    }

    public ToolCallbackProvider customMcpToolCallbackProvider() {
        return SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClientList).build();
    }

    private McpSyncClient buildClient(String name, ServerEntry entry) {
        McpClientTransport transport = buildTransport(entry);

        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder(name, "1.0.0").build())
                .requestTimeout(Duration.ofSeconds(20))
                .build();

        client.initialize();
        return client;
    }

    private McpClientTransport buildTransport(ServerEntry entry) {
        String type = entry.type != null ? entry.type().toLowerCase() : entry.url == null ? "stdio" : entry.url.contains("/sse") ? "sse" : "http";

        return switch (type) {
            case "http", "streamable-http" -> {
                UrlParts parts = splitUrl(entry.url());
                yield HttpClientStreamableHttpTransport.builder(parts.baseUri())
                        .endpoint(parts.path())
                        .build();
            }
            case "sse" -> buildSseTransport(entry);
            case "stdio" -> {
                ServerParameters params = ServerParameters.builder(entry.command())
                        .args(entry.args() != null ? entry.args() : List.of())
                        .env(entry.env() != null ? entry.env() : Map.of())
                        .build();
                yield new StdioClientTransport(params, McpJsonDefaults.getMapper());
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported MCP server type '" + entry.type() + "'. Expected one of: stdio, sse, http/streamable-http.");
        };
    }

    /**
     * Legacy 2024-11-05 HTTP+SSE transport. Deprecated by the MCP Java SDK in favor of
     * Streamable HTTP, but kept here deliberately for servers that haven't migrated yet.
     */
    @SuppressWarnings("deprecation")
    private McpClientTransport buildSseTransport(ServerEntry entry) {
        UrlParts parts = splitUrl(entry.url());
        return HttpClientSseClientTransport.builder(parts.baseUri())
                .sseEndpoint(parts.path())
                .build();
    }

    /**
     * Splits a full URL (as used by Claude Code / Cursor style configs, e.g.
     * "http://localhost:8080/mcp?token=abc") into a base URI ("http://localhost:8080")
     * and a path+query suffix ("/mcp?token=abc"), since the MCP Java SDK's HTTP
     * transport builders expect them separately.
     */
    private UrlParts splitUrl(String fullUrl) {
        if (fullUrl == null || fullUrl.isBlank()) {
            throw new IllegalArgumentException("Missing 'url' for an http/sse MCP server entry");
        }
        URI uri = URI.create(fullUrl);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        return new UrlParts(base, path);
    }

    private record UrlParts(String baseUri, String path) {
    }

    private record ClaudeDesktopConfig(Map<String, ServerEntry> mcpServers) {
    }

    private record ServerEntry(
            String type,             // null/"stdio" (default), "sse", "http"/"streamable-http"
            String command,          // stdio
            List<String> args,       // stdio
            Map<String, String> env, // stdio
            String url,               // sse / http — single full endpoint URL
            String envFile
    ) {
        @Override
        public Map<String, String> env() {
            HashMap<String, String> envMap = new HashMap<>();
            if (envFile != null) {
                loadEnv(envFile, envMap);
            }
            if (env != null) {
                envMap.putAll(env);
            }
            return envMap;
        }

        private void loadEnv(String filePath, Map<String, String> envMap) {
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();

                    // Skip empty lines and comments
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    // Split by the first '=' character
                    int delimiterIndex = line.indexOf('=');
                    if (delimiterIndex > 0) {
                        String key = line.substring(0, delimiterIndex).trim();
                        String value = line.substring(delimiterIndex + 1).trim();

                        // Strip optional wrapping quotes around the value
                        if (value.startsWith("\"") && value.endsWith("\"") ||
                                value.startsWith("'") && value.endsWith("'")) {
                            value = value.substring(1, value.length() - 1);
                        }

                        envMap.put(key, value);

                        // Optional: Push to System properties so Spring/Java can access it globally
                        System.setProperty(key, value);
                    }
                }
            } catch (IOException e) {
                log.error("Could not read .env file: {}", e.getMessage());
            }
        }
    }
}