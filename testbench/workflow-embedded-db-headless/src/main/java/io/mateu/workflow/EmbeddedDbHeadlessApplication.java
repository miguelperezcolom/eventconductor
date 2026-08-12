package io.mateu.workflow;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@WorkflowEmbeddedApplication
public class EmbeddedDbHeadlessApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedDbHeadlessApplication.class, args);
    }

}
