package io.mateu.workflow;

import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class McpToolsConfig {

    final List<McpTools> tools;
    final List<McpSystemContext> contexts;

    @Bean
    public ToolCallbackProvider workflowToolCallbackProvider() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }

    /**
     * Exposes a "system-context" MCP Prompt so the ia-agent-service can build a
     * dynamic system prompt by collecting context from every connected MCP server.
     */
    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> systemContextPrompts() {
        String combined = contexts.stream()
                .map(McpSystemContext::getSystemContext)
                .collect(Collectors.joining("\n\n"));

        var prompt = new McpSchema.Prompt(
                "system-context",
                "Domain context for the AI agent about this server's capabilities",
                List.of());

        return List.of(new McpServerFeatures.SyncPromptSpecification(prompt,
                (exchange, request) -> new McpSchema.GetPromptResult(
                        "System context",
                        List.of(new McpSchema.PromptMessage(
                                McpSchema.Role.USER,
                                new McpSchema.TextContent(combined))))));
    }
}
