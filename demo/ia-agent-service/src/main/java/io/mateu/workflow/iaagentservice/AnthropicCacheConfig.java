package io.mateu.workflow.iaagentservice;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class AnthropicCacheConfig {

    @Bean
    RestClientCustomizer anthropicCacheCustomizer(AnthropicCacheInterceptor cacheInterceptor) {
        return builder -> builder
                // BufferingClientHttpRequestFactory lets the interceptor AND Spring AI
                // both read the same response body (otherwise the stream is consumed once).
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .requestInterceptors(list -> list.add(cacheInterceptor));
    }
}
