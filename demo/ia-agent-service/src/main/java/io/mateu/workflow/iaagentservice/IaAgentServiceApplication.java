package io.mateu.workflow.iaagentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(McpSseConnectionProperties.class)
public class IaAgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IaAgentServiceApplication.class, args);
    }

}
