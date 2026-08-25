package io.mateu.workflow.embeddedgit;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@WorkflowEmbeddedApplication
public class EmbeddedGitApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedGitApplication.class, args);
    }

}
