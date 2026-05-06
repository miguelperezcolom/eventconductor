package io.mateu.workflow.iaagentservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wraps MCP tool callbacks so they run on a regular thread pool instead of the Netty event loop.
 *
 * Root cause: AnthropicChatModel processes responses (and invokes tool callbacks) on the Netty
 * event loop thread or-http-epoll-*. Reactor forbids .block() on event loop threads.
 * SyncMcpToolCallbackProvider.call() internally calls McpSyncClient.callTool().block(),
 * which throws BlockingOperationError.
 *
 * Fix: submit the actual tool call to a dedicated thread pool; block the event loop thread
 * with Future.get() (plain Java, not Reactor — no blocking check applies).
 */
@Configuration
public class McpToolConfig {

    private static final Logger log = LoggerFactory.getLogger(McpToolConfig.class);

    @Bean
    @Primary
    public ToolCallbackProvider wrappedMcpToolCallbackProvider(SyncMcpToolCallbackProvider delegate) {
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-tool-executor");
            t.setDaemon(true);
            return t;
        });

        ToolCallback[] wrapped = Arrays.stream(delegate.getToolCallbacks())
                .map(cb -> (ToolCallback) new ToolCallback() {
                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
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
                        } catch (java.util.concurrent.ExecutionException e) {
                            log.error("MCP tool {} execution error", toolName, e.getCause());
                            throw new RuntimeException("Tool " + toolName + " failed: " + e.getCause().getMessage(), e.getCause());
                        } catch (Exception e) {
                            log.error("MCP tool {} call failed", toolName, e);
                            throw new RuntimeException("Tool " + toolName + " timed out or failed", e);
                        }
                    }
                })
                .toArray(ToolCallback[]::new);

        log.info("Registered {} MCP tools with blocking-executor wrapper", wrapped.length);
        return () -> wrapped;
    }
}
