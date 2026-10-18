package io.mateu.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    @SuppressWarnings("deprecation")
    void aGeneratedPasswordIsNeverPrinted() {
        var config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "user", "admin");
        ReflectionTestUtils.setField(config, "password", "");
        var out = new ByteArrayOutputStream();
        var original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        String generated;
        try {
            // NoOp keeps the generated password readable, so the test can look for it in the output.
            generated = config.userDetailsManager(NoOpPasswordEncoder.getInstance())
                    .loadUserByUsername("admin").getPassword();
        } finally {
            System.setOut(original);
        }
        assertThat(generated).isNotBlank();
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain(generated);
    }
}
