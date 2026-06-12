package io.mateu.workflow.embedded;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@WorkflowEmbeddedApplication
public class EmbeddedApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedApplication.class, args);
    }

}
