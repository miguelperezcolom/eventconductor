package io.mateu.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.UUID;

/**
 * HTTP Basic auth for the orchestrator app.
 *
 * Disabled when {@code eventconductor.security.enabled=false}. In that case
 * the filter chain is permitAll — useful for tests and local development.
 *
 * Configuration:
 * <pre>
 *   eventconductor.security.enabled = true|false  (default true)
 *   eventconductor.security.user    = admin       (default)
 *   eventconductor.security.password = &lt;secret&gt;  (mandatory in prod; a random
 *                                                  one is generated and logged
 *                                                  if blank)
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "eventconductor.security")
public class SecurityConfig {

    @Value("${eventconductor.security.enabled:true}")
    private boolean enabled;

    @Value("${eventconductor.security.user:admin}")
    private String user;

    @Value("${eventconductor.security.password:}")
    private String password;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsManager(PasswordEncoder encoder) {
        String effectivePassword = password;
        if (effectivePassword == null || effectivePassword.isBlank()) {
            effectivePassword = UUID.randomUUID().toString();
            System.out.println(
                "\n=========================================================\n" +
                " EventConductor: no SECURITY_PASSWORD provided — generated\n" +
                " a random one for user '" + user + "':\n" +
                "    " + effectivePassword + "\n" +
                " Set SECURITY_PASSWORD to a stable value in production.\n" +
                "=========================================================\n");
        }
        UserDetails admin = User.withUsername(user)
            .password(encoder.encode(effectivePassword))
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (!enabled) {
            return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }
        return http
            // The engine is a service exposed over JSON/SSE — no browser-form
            // login flow — so CSRF tokens would only get in the way.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Publicly readable health probes for Kubernetes / load balancers.
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .httpBasic(httpBasic -> {})
            .build();
    }
}
