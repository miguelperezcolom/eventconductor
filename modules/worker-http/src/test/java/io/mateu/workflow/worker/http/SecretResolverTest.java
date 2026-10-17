package io.mateu.workflow.worker.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretResolverTest {

    @Test
    void referencesResolveAndPlainValuesPassThrough() {
        var resolver = new SecretResolver(Map.of("A", "alpha")::get);
        assertThat(resolver.resolve("${secret:A}")).isEqualTo("alpha");
        assertThat(resolver.resolve("plain")).isEqualTo("plain");
        assertThat(resolver.resolve(null)).isNull();
        assertThatThrownBy(() -> resolver.resolve("${secret:B}")).hasMessageContaining("Secret 'B'");
    }

    @Test
    void theEnvironmentFallbackCanBeTurnedOff() {
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("DB_PASSWORD", "fake-db-value");
        var properties = new HttpWorkerProperties();
        properties.getSecrets().put("PARTNER", "p");

        var withFallback = new SecretResolver(HttpWorkerAutoConfiguration.secretLookup(properties, environment));
        assertThat(withFallback.resolve("${secret:PARTNER}")).isEqualTo("p");
        assertThat(withFallback.resolve("${secret:DB_PASSWORD}")).as("documented default").isEqualTo("fake-db-value");

        properties.setSecretsFromEnvironment(false);
        var listedOnly = new SecretResolver(HttpWorkerAutoConfiguration.secretLookup(properties, environment));
        assertThat(listedOnly.resolve("${secret:PARTNER}")).isEqualTo("p");
        assertThatThrownBy(() -> listedOnly.resolve("${secret:DB_PASSWORD}"))
                .isInstanceOf(SecretResolver.MissingSecretException.class);
    }
}
