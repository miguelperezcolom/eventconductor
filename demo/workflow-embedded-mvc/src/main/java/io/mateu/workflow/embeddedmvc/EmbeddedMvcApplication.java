package io.mateu.workflow.embeddedmvc;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@WorkflowEmbeddedApplication
public class EmbeddedMvcApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedMvcApplication.class, args);
    }

}
