package io.mateu.workflow.iaagentservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads spring.ai.mcp.client.sse.connections so we can create per-request MCP clients
 * without relying on Spring AI's auto-configured (persistent) McpSyncClient beans.
 */
@ConfigurationProperties(prefix = "spring.ai.mcp.client.sse")
public class McpSseConnectionProperties {

    private Map<String, SseConnection> connections = new LinkedHashMap<>();

    public Map<String, SseConnection> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, SseConnection> connections) {
        this.connections = connections;
    }

    public static class SseConnection {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
