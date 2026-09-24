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
}
