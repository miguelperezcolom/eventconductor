package io.mateu.workflow.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Dedicated configuration class to handle component scanning of the EventConductor engine packages.
 * <p>
 * Ensures that the core engine classes are always scanned while strictly excluding the UI adapter layer,
 * which requires a servlet context and JPA persistence.
 */
@Configuration
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
public class WorkflowEngineComponentScanConfiguration {
}
