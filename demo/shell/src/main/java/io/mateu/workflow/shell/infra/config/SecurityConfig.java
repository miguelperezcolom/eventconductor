package io.mateu.workflow.shell.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The static bundle, served without Spring Security's cache headers.
     *
     * <p>Those headers are {@code no-cache, no-store, max-age=0, must-revalidate}, applied to every
     * response by default, and {@code no-store} means what it says: the browser is forbidden from
     * keeping the file at all. The shell's UI bundle is 3.5 MB of JavaScript, so every single page
     * load re-downloaded all of it — the cache was not missing, it was prohibited.
     *
     * <p>Only the cache-control writer is turned off. Everything else Spring Security stamps on a
     * response — nosniff, frame options, HSTS, referrer policy — still applies here, and these
     * files stay public: they are already served to anyone who loads the login page.
     *
     * <p>What replaces it is {@code spring.web.resources.cache}: revalidate rather than expire,
     * because the filenames carry no content hash and a long max-age would strand browsers on an
     * old bundle after a release.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain staticAssetsFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/assets/**", "/myassets/**", "/images/**", "/js/**",
                    "/vite.svg", "/favicon.ico")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .headers(headers -> headers.cacheControl(HeadersConfigurer.CacheControlConfig::disable));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/mateu/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}
