package io.mateu.workflow.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SignaturesTest {

    @Test
    void computesGitHubsDocumentedExample() {
        // https://docs.github.com/webhooks/using-webhooks/validating-webhook-deliveries
        var body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        var hex = Signatures.hmacSha256Hex("It's a Secret to Everybody", body);

        assertThat(hex).isEqualTo("757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
        assertThat(Signatures.matches("It's a Secret to Everybody", body, "sha256=" + hex)).isTrue();
        assertThat(Signatures.matches("It's a Secret to Everybody", body, hex.toUpperCase())).isTrue();
        assertThat(Signatures.matches("It's a Secret to Everybody", body, "sha256=00" + hex.substring(2))).isFalse();
        assertThat(Signatures.matches("It's a Secret to Everybody", body, null)).isFalse();
        assertThat(Signatures.matches("It's a Secret to Everybody", body, " ")).isFalse();
        assertThat(Signatures.matches("other", body, "sha256=" + hex)).isFalse();
    }

    @Test
    void anAbsentBodyIsSignedAsAnEmptyOne() {
        assertThat(Signatures.hmacSha256Hex("k", null)).isEqualTo(Signatures.hmacSha256Hex("k", new byte[0]));
    }
}
