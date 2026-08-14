package io.mateu.workflow.autoconfigure;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.annotation.AliasFor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Drop-in replacement for {@code @SpringBootApplication} for embedded-mode EventConductor apps.
 * <p>
 * Performs standard Spring Boot component scanning over the calling application's packages
 * while strictly excluding the UI adapter layer, and automatically imports the EventConductor
 * core engine classes, registers necessary JPA repositories, and configures exclusions.
 *
 * <p>Uses {@code @SpringBootConfiguration + @EnableAutoConfiguration} instead of
 * {@code @SpringBootApplication} to avoid the duplicate {@code @ComponentScan} that
 * {@code @SpringBootApplication} would add, which would pick up UI classes before
 * the exclusion filter can prevent it.
 *
 * <p>Delegates engine-specific scanning to {@link WorkflowEngineComponentScanConfiguration}
 * and JPA entity auto-configuration mapping to {@link WorkflowEmbeddedApplicationRegistrar}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "io\\.mateu\\.workflow\\.infra\\.in\\.ui\\..*"
                )
        }
)
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@Import({
        WorkflowEngineComponentScanConfiguration.class,
        WorkflowEmbeddedApplicationRegistrar.class
})
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
    String[] scanBasePackages() default {};

    /**
     * Type-safe alternative to {@link #scanBasePackages()} for specifying the packages
     * to scan for annotated components.
     * @return the base package classes to scan
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};

    /**
     * Base packages to scan for annotated JPA repositories.
     * @return the base packages to scan for JPA repositories
     */
    @AliasFor(annotation = EnableJpaRepositories.class, attribute = "basePackages")
    String[] jpaRepositoryBasePackages() default {"io.mateu.workflow.infra.out.persistence"};
}
