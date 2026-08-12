package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.annotation.AliasFor;

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
 * <p>Uses {@code @SpringBootConfiguration + @EnableAutoConfiguration} instead of
 * {@code @SpringBootApplication} to avoid the duplicate {@code @ComponentScan} that
 * {@code @SpringBootApplication} would add, which would pick up UI classes before
 * the exclusion filter can prevent it.
 *
 * <p>Integrates with {@link WorkflowEmbeddedApplicationRegistrar} to automatically scan the
 * user's application package for standard Spring components and register auto-configuration packages
 * for JPA/Hibernate entities and Spring Data repositories.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration
@EnableAutoConfiguration
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
)
@Import(WorkflowEmbeddedApplicationRegistrar.class)
public @interface WorkflowEmbeddedApplication {

    /**
     * Exclude specific auto-configuration classes such that they will never be applied.
     * @return the classes to exclude
     */
    @AliasFor(annotation = EnableAutoConfiguration.class, attribute = "exclude")
    Class<?>[] exclude() default {};

    /**
     * Exclude specific auto-configuration class names such that they will never be applied.
     * @return the class names to exclude
     */
    @AliasFor(annotation = EnableAutoConfiguration.class, attribute = "excludeName")
    String[] excludeName() default {};

    /**
     * Base packages to scan for annotated components.
     * @return the base packages to scan
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] scanBasePackages() default {"io.mateu.workflow"};

    /**
     * Type-safe alternative to {@link #scanBasePackages()} for specifying the packages
     * to scan for annotated components.
     * @return the base package classes to scan
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};
}
