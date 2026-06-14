package io.mateu.workflow.embeddedgit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "io.mateu.workflow")
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow")
public class EmbeddedGitApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedGitApplication.class, args);
    }

}
