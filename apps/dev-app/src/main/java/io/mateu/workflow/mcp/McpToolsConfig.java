package io.mateu.workflow.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider workflowToolCallbackProvider(
            WorkflowMcpTools workflowTools,
            FormsMcpTools formsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(workflowTools, formsTools)
                .build();
    }
}
