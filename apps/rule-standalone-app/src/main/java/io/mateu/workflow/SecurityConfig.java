package io.mateu.workflow;

import org.springframework.beans.factory.annotation.Value;
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
 * HTTP Basic auth for the rules app. See orchestrator's SecurityConfig for
 * configuration details — same property keys apply.
 */
@Configuration
public class SecurityConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

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
            // The password is not shown: stdout ends up in log aggregators, where anyone reading the
            // logs would hold an admin credential. Nobody can log in until one is set.
            if (enabled) {
                log.warn("EventConductor (rules): no SECURITY_PASSWORD (eventconductor.security.password) is set, so user '{}'"
                        + " got a random password that is not shown anywhere: nobody can log in. Set SECURITY_PASSWORD"
                        + " to a password of your choosing (in Kubernetes, from a Secret), or SECURITY_ENABLED=false"
                        + " for local experimentation only.", user);
            }
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
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .httpBasic(httpBasic -> {})
            .build();
    }
}
