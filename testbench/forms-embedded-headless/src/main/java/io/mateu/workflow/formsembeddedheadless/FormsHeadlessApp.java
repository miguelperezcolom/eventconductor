package io.mateu.workflow.formsembeddedheadless;

import io.mateu.workflow.autoconfigure.FormsEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@FormsEmbeddedApplication
public class FormsHeadlessApp {

    public static void main(String[] args) {
        SpringApplication.run(FormsHeadlessApp.class, args);
    }

}
