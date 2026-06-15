package io.mateu.workflow.formsembedded;

import io.mateu.workflow.autoconfigure.FormsEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@FormsEmbeddedApplication
public class FormsApp {

    public static void main(String[] args) {
        SpringApplication.run(FormsApp.class, args);
    }

}
