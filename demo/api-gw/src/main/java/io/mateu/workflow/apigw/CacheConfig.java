package io.mateu.workflow.apigw;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.mateu.demo.lib.GetAuthInfoReply;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, GetAuthInfoReply> authCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5)) // El permiso expira en 5 min
                .maximumSize(10000) // Guardamos hasta 10,000 usuarios en memoria
                .build();
    }
}