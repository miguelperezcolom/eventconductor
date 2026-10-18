package io.mateu.workflow.webhook;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedWebhookPolicyTest {

    @Test
    void anUnsignedRequestIsRefusedWhenTheCallerSaysSo() {
        var refusal = UnsignedWebhookPolicy.refusal("rules.git-import.webhook-secret");
        assertThat(refusal).contains("rules.git-import.webhook-secret").contains(UnsignedWebhookPolicy.ALLOW_ANONYMOUS);
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITHUB, null, h -> null, new byte[0], refusal))
                .isInstanceOf(WebhookSignatureVerifier.WebhookVerificationException.class)
                .hasMessage(refusal);
        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITHUB, " ", h -> null, new byte[0], null))
                .doesNotThrowAnyException();
        // With a secret, the refusal text plays no part: the token decides.
        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.GENERIC, "tok",
                h -> "X-Webhook-Token".equals(h) ? "tok" : null, new byte[0], refusal)).doesNotThrowAnyException();
    }

    @Test
    void theFilterChainIsLookedUpByName() {
        var loader = getClass().getClassLoader();
        // Spring Security is not on this module's classpath: no chain, whatever the context says.
        assertThat(UnsignedWebhookPolicy.filterChainPresent(loader, type -> true)).isFalse();
        // Present: the context is asked about that very type.
        var stand_in = UnsignedWebhookPolicyTest.class;
        assertThat(UnsignedWebhookPolicy.beanPresent(stand_in.getName(), loader, type -> type == stand_in)).isTrue();
        assertThat(UnsignedWebhookPolicy.beanPresent(stand_in.getName(), loader, type -> false)).isFalse();
    }

    @Test
    void aBodyIsReadUpToTheLimit() throws Exception {
        var body = "0123456789".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookBodies.read(new ByteArrayInputStream(body), 10, 10)).isEqualTo(body);
        assertThat(WebhookBodies.read(null, -1, 10)).isEmpty();
        assertThatThrownBy(() -> WebhookBodies.read(new ByteArrayInputStream(body), 11, 10))
                .isInstanceOf(WebhookBodies.TooLargeException.class);
        // Chunked (no Content-Length): counted as it is read.
        assertThatThrownBy(() -> WebhookBodies.read(new ByteArrayInputStream(body), -1, 9))
                .isInstanceOf(WebhookBodies.TooLargeException.class).hasMessageContaining("9 byte");
    }
}
