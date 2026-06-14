package io.mateu.workflow.embeddedgit;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
public class EmbeddedGitApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedGitApplication.class, args);
    }

}
