package io.mateu.workflow.controlplaneservice;

import io.mateu.workflow.controlplaneservice.infra.CloudflareProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CloudflareProperties.class)
public class ControlPlaneServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneServiceApplication.class, args);
    }

}
