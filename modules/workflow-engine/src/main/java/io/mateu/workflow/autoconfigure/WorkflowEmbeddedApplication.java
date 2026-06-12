package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Drop-in replacement for {@code @SpringBootApplication} for embedded-mode EventConductor apps.
 * Scans the full {@code io.mateu.workflow} package tree while excluding the UI adapter layer,
 * which requires a servlet web context and JPA persistence and must not be loaded in headless
 * or in-memory deployments.
 *
 * Uses {@code @SpringBootConfiguration + @EnableAutoConfiguration} instead of
 * {@code @SpringBootApplication} to avoid the duplicate {@code @ComponentScan} that
 * {@code @SpringBootApplication} would add, which would pick up UI classes before
 * the exclusion filter can prevent it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "io.mateu.workflow",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
        )
)
public @interface WorkflowEmbeddedApplication {
}
