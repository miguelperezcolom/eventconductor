package com.example.series;

import io.mateu.workflow.autoconfigure.EnableWorkflowUi;
import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The series app: the engine embedded in a Spring Boot app, with its console and the forms console.
 *
 * <pre>mvn spring-boot:run</pre>
 *
 * then http://localhost:8096.
 *
 * <p>{@code @WorkflowEmbeddedApplication} (not {@code @SpringBootApplication}) wires the engine in embedded mode;
 * {@code @EnableWorkflowUi} adds its console. The two JPA annotations bring the engine's own entities and
 * repositories in, which {@code workflow.persistence=jpa} needs.
 */
@WorkflowEmbeddedApplication
@EnableWorkflowUi
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class VideoSeriesApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoSeriesApplication.class, args);
    }
}
