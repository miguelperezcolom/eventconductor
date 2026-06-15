package io.mateu.workflow.formsembeddedmvc;

import io.mateu.workflow.autoconfigure.FormsEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@FormsEmbeddedApplication
public class FormsMvcApp {

    public static void main(String[] args) {
        SpringApplication.run(FormsMvcApp.class, args);
    }

}
