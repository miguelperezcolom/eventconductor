package io.mateu.workflow.booking.infra.in.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider bookingToolCallbackProvider(BookingMcpTools bookingTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bookingTools)
                .build();
    }
}
