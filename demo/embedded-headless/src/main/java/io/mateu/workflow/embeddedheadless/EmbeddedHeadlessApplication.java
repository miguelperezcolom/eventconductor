package io.mateu.workflow.embeddedheadless;

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
public class EmbeddedHeadlessApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedHeadlessApplication.class, args);
    }

}
