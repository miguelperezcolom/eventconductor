package io.mateu.workflow.embedded;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
        basePackages = "io.mateu.workflow",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
        )
)
public class EmbeddedApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedApplication.class, args);
    }

}
