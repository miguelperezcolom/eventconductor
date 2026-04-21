package io.mateu.workflow.staticcontentserver;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class StaticResourceConfiguration implements WebFluxConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("file:/home/mperezco/IdeaProjects/eventconductor/demo/static/")
                .setCacheControl(org.springframework.http.CacheControl.noCache());
    }
}