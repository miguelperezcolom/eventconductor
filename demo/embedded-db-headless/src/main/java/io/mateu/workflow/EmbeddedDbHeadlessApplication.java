package io.mateu.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(
        basePackages = "io.mateu.workflow",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
        )
)
@EnableJpaRepositories(basePackages = "io.mateu.workflow")
public class EmbeddedDbHeadlessApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedDbHeadlessApplication.class, args);
    }

}
