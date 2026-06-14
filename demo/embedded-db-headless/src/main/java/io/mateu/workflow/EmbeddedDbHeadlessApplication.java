package io.mateu.workflow;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow")
public class EmbeddedDbHeadlessApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedDbHeadlessApplication.class, args);
    }

}
