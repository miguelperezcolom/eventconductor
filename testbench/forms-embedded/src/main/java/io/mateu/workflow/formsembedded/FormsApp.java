package io.mateu.workflow.formsembedded;

import io.mateu.workflow.autoconfigure.FormsEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// @FormsEmbeddedApplication narrows the scan to what embedded mode needs; the two JPA annotations
// bring the forms-engine's own entities and repositories back in, which jpa persistence requires
// and the scan would otherwise leave out (mirrors workflow-embedded's EmbeddedApplication).
@FormsEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class FormsApp {

    public static void main(String[] args) {
        SpringApplication.run(FormsApp.class, args);
    }

}
