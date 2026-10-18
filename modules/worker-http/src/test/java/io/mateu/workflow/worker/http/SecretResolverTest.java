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
    void secretsComeOnlyFromTheListUnlessTheEnvironmentFallbackIsTurnedOn() {
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("DB_PASSWORD", "fake-db-value");
        var properties = new HttpWorkerProperties();
        properties.getSecrets().put("PARTNER", "p");

        // The default since 2.27.0: only what the operator listed under workflow.http.secrets.
        var listedOnly = new SecretResolver(HttpWorkerAutoConfiguration.secretLookup(properties, environment));
        assertThat(listedOnly.resolve("${secret:PARTNER}")).isEqualTo("p");
        assertThatThrownBy(() -> listedOnly.resolve("${secret:DB_PASSWORD}"))
                .as("an environment value the operator did not list")
                .isInstanceOf(SecretResolver.MissingSecretException.class)
                .hasMessageContaining("workflow.http.secrets.DB_PASSWORD")
                .as("no environment variable suggested when the environment is not consulted")
                .hasMessageNotContaining("environment variable");

        properties.setSecretsFromEnvironment(true);
        var withFallback = new SecretResolver(HttpWorkerAutoConfiguration.secretLookup(properties, environment), true);
        assertThat(withFallback.resolve("${secret:PARTNER}")).isEqualTo("p");
        assertThat(withFallback.resolve("${secret:DB_PASSWORD}")).as("opted back in").isEqualTo("fake-db-value");
        assertThatThrownBy(() -> withFallback.resolve("${secret:NOWHERE}"))
                .isInstanceOf(SecretResolver.MissingSecretException.class)
                .hasMessageContaining("workflow.http.secrets.NOWHERE")
                .hasMessageContaining("property/environment variable");
    }
}
