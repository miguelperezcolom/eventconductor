package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;

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
@ComponentScans({
        @ComponentScan(
                basePackages = "io.mateu.workflow",
                excludeFilters = {
                        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                        @ComponentScan.Filter(
                                type = FilterType.REGEX,
                                pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
                        )
                }
        ),
        /**
         * The package of the annotated application class, scanned the way
         * {@code @SpringBootApplication} scans it. An explicit {@code basePackages} above overrides
         * the default, so without this second scan a user's own beans were invisible unless their
         * app class lived under {@code io.mateu.*}.
         *
         * <p>The two filters are not optional decoration: {@code @SpringBootApplication} carries
         * both on its scan. Without {@link TypeExcludeFilter} the Boot test slices stop working in
         * the user's package — a {@code @TestConfiguration} gets picked up where the slice means to
         * exclude it. Without {@link AutoConfigurationExcludeFilter} a user {@code @Configuration}
         * that is also registered as an auto-configuration is defined twice, which is a bean
         * definition clash or an auto-configuration that runs at the wrong moment.
         */
        @ComponentScan(
                excludeFilters = {
                        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
                }
        )
})
public @interface WorkflowEmbeddedApplication {
}
