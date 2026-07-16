package io.mateu.workflow.rulesembeddedmvc;

import io.mateu.workflow.autoconfigure.RulesEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@RulesEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class RulesMvcApp {

    public static void main(String[] args) {
        SpringApplication.run(RulesMvcApp.class, args);
    }

}
