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
        @ComponentScan
})
public @interface FormsEmbeddedApplication {
}
