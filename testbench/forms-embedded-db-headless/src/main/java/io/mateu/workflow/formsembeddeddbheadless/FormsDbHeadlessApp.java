package io.mateu.workflow.formsembeddeddbheadless;

import io.mateu.workflow.autoconfigure.FormsEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@FormsEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class FormsDbHeadlessApp {

    public static void main(String[] args) {
        SpringApplication.run(FormsDbHeadlessApp.class, args);
    }

}
