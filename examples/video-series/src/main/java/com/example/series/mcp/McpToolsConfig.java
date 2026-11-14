package com.example.series.mcp;

import io.mateu.workflow.mcp.McpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Publishes the engine's MCP tools (and the forms engine's) on this app's MCP server (/sse). The MCP server is
 * opt-in twice: the spring-ai-starter-mcp-server-webmvc dependency serves the endpoint, and this bean is what
 * puts EventConductor's tools on it — every {@link McpTools} bean (listProcesses, getProcessDetails,
 * publishInboundEvent, listSchedules…). Without it an embedded app's MCP server answers with no tools at all, as
 * the standalone orchestrator's McpToolsConfig does for that app.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    ToolCallbackProvider eventConductorTools(List<McpTools> tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools.toArray()).build();
    }
}
