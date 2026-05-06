package io.mateu.workflow;

import io.mateu.workflow.mcp.McpTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class McpToolsConfig {

    final List<McpTools> tools;

    @Bean
    public ToolCallbackProvider workflowToolCallbackProvider() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }
}
