package io.mateu.workflow.rulesembeddedheadless;

import io.mateu.workflow.autoconfigure.RulesEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@RulesEmbeddedApplication
public class RulesHeadlessApp {

    public static void main(String[] args) {
        SpringApplication.run(RulesHeadlessApp.class, args);
    }

}
