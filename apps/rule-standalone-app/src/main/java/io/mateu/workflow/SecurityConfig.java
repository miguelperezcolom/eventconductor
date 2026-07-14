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
                " EventConductor (rules): no SECURITY_PASSWORD provided —\n" +
                " generated a random one for user '" + user + "':\n" +
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
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .httpBasic(httpBasic -> {})
            .build();
    }
}
