package io.mateu.workflow.embeddedheadless;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@WorkflowEmbeddedApplication
public class EmbeddedHeadlessApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedHeadlessApplication.class, args);
    }

}
