package io.mateu.workflow.iaagentservice;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Creates a fresh set of McpSyncClient connections for every prompt.
 *
 * Why: Spring AI's auto-configured McpSyncClient holds a persistent SSE connection.
 * When that connection breaks the client is permanently broken and all subsequent
 * tool calls fail.  Opening a new connection per request avoids this: each prompt
 * gets a healthy transport, and the transport is discarded immediately afterwards.
 *
 * System prompt: after connecting to each server this factory reads the MCP Prompt
 * named "system-context" (if the server exposes it) and returns the combined text
 * via {@link PerRequestTools#getServerSystemContext()}.  The controller appends this
 * to the local base prompt to build the final system message for the LLM.
 *
 * Blocking issue: AnthropicChatModel invokes tool callbacks on the Netty event-loop
 * thread. Reactor forbids .block() there.  SyncMcpToolCallbackProvider.call()
 * internally calls McpSyncClient.callTool().block().
 * Fix: submit the actual call to a dedicated thread pool and wait with plain
 * Future.get() — no blocking check applies to plain Java futures.
 */
@Component
public class PerRequestMcpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(PerRequestMcpClientFactory.class);
    private static final String SYSTEM_CONTEXT_PROMPT = "system-context";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final List<String> serverUrls;
    private final ExecutorService executor;

    public PerRequestMcpClientFactory(McpSseConnectionProperties props) {
        this.serverUrls = props.getConnections().values().stream()
                .map(McpSseConnectionProperties.SseConnection::getUrl)
                .toList();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-tool-executor");
            t.setDaemon(true);
            return t;
        });
        log.info("PerRequestMcpClientFactory ready. MCP servers: {}", serverUrls);
    }

    /**
     * Opens a fresh connection to every configured MCP server, initialises the MCP
     * session, collects tool callbacks and the "system-context" prompt from each
     * server.  Callers MUST call {@link PerRequestTools#close()} when the prompt
     * finishes (use try-with-resources).
     *
     * @param authorizationHeader value of the incoming Authorization header (may be
     *                            null or blank); when present it is forwarded to every
     *                            MCP server so they can enforce their own authorization.
     */
    public PerRequestTools createTools(String authorizationHeader) {
        List<McpSyncClient> clients = new ArrayList<>();
        List<String> serverContexts = new ArrayList<>();

        for (String url : serverUrls) {
            try {
                var transportBuilder = HttpClientSseClientTransport.builder(url);
                if (authorizationHeader != null && !authorizationHeader.isBlank()) {
                    transportBuilder.customizeRequest(
                            rb -> rb.header("Authorization", authorizationHeader));
                }
                var transport = transportBuilder.build();
                McpSyncClient client = McpClient.sync(transport)
                        .requestTimeout(REQUEST_TIMEOUT)
                        .clientInfo(new McpSchema.Implementation("ia-agent-service", "1.0"))
                        .build();
                client.initialize();
                clients.add(client);
                log.debug("MCP client connected: {}", url);

                String ctx = readSystemContext(client, url);
                if (ctx != null) {
                    serverContexts.add(ctx);
                }
            } catch (Exception e) {
                log.warn("Could not connect to MCP server {} — skipping: {}", url, e.getMessage());
            }
        }

        ToolCallback[] rawCallbacks = new SyncMcpToolCallbackProvider(clients).getToolCallbacks();
        ToolCallback[] wrapped = wrapWithExecutor(rawCallbacks);
        log.info("Per-request MCP tools ready: {} tools from {}/{} servers",
                wrapped.length, clients.size(), serverUrls.size());
        return new PerRequestTools(clients, wrapped, serverContexts);
    }

    private String readSystemContext(McpSyncClient client, String url) {
        try {
            boolean hasPrompt = client.listPrompts().prompts().stream()
                    .anyMatch(p -> SYSTEM_CONTEXT_PROMPT.equals(p.name()));
            if (!hasPrompt) {
                return null;
            }
            var result = client.getPrompt(
                    new McpSchema.GetPromptRequest(SYSTEM_CONTEXT_PROMPT, Map.of()));
            String text = result.messages().stream()
                    .filter(m -> m.content() instanceof McpSchema.TextContent)
                    .map(m -> ((McpSchema.TextContent) m.content()).text())
                    .findFirst()
                    .orElse(null);
            if (text != null) {
                log.debug("System context from {}: {} chars", url, text.length());
            }
            return text;
        } catch (Exception e) {
            log.warn("Could not read system-context prompt from {}: {}", url, e.getMessage());
            return null;
        }
    }

    private ToolCallback[] wrapWithExecutor(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(cb -> (ToolCallback) new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return cb.getToolDefinition();
                    }

                    @Override
                    public String call(String toolInput) {
                        String toolName = cb.getToolDefinition().name();
                        log.info("MCP tool call: {} input={}", toolName, toolInput);
                        try {
                            String result = executor.submit(() -> cb.call(toolInput))
                                    .get(60, TimeUnit.SECONDS);
                            log.info("MCP tool result: {} -> {}", toolName, result);
                            return result;
                        } catch (ExecutionException e) {
                            log.error("MCP tool {} execution error", toolName, e.getCause());
                            throw new RuntimeException(
                                    "Tool " + toolName + " failed: " + e.getCause().getMessage(), e.getCause());
                        } catch (Exception e) {
                            log.error("MCP tool {} call failed", toolName, e);
                            throw new RuntimeException("Tool " + toolName + " timed out or failed", e);
                        }
                    }
                })
                .toArray(ToolCallback[]::new);
    }

    /** Holds per-request MCP clients, wrapped tool callbacks and server system contexts. */
    public static class PerRequestTools implements AutoCloseable {

        private final List<McpSyncClient> clients;
        private final ToolCallback[] callbacks;
        private final List<String> serverContexts;

        PerRequestTools(List<McpSyncClient> clients, ToolCallback[] callbacks, List<String> serverContexts) {
            this.clients = clients;
            this.callbacks = callbacks;
            this.serverContexts = serverContexts;
        }

        public ToolCallback[] getCallbacks() {
            return callbacks;
        }

        /**
         * Returns the combined system-context text contributed by all connected MCP
         * servers, or an empty string if no server exposed the "system-context" prompt.
         */
        public String getServerSystemContext() {
            return String.join("\n\n", serverContexts);
        }

        @Override
        public void close() {
            for (McpSyncClient client : clients) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("Error closing MCP client: {}", e.getMessage());
                }
            }
        }
    }
}
