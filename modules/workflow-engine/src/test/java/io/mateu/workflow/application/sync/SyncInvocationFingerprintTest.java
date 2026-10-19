package io.mateu.workflow.application.sync;

import io.mateu.workflow.application.services.SensitiveFixtures;
import io.mateu.workflow.crypto.VariableEncryption;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The idempotency fingerprint of a synchronous invocation whose request carries a card number. */
class SyncInvocationFingerprintTest {

    static SyncInvocationService.StartRequest request(String card) {
        return SyncInvocationServiceHashTest.request("bk", Map.of("cardNumber", card), null);
    }

    @Test
    void withKeysTheStoredFingerprintIsKeyedAndCannotBeRecomputedFromTheRequestAlone() {
        var keys = SensitiveFixtures.keys();
        var canonical = SyncInvocationService.canonicalOf(request(SensitiveFixtures.CARD));
        var stored = keys.fingerprint(canonical);
        assertThat(stored).startsWith("h1:test-key:")
                .isNotEqualTo(SyncInvocationService.hashOf(request(SensitiveFixtures.CARD)));
        assertThat(keys.fingerprintMatches(stored, canonical)).isTrue();
        assertThat(keys.fingerprintMatches(stored, SyncInvocationService.canonicalOf(request("4000000000000002")))).isFalse();
        // Without keys: the digest it always was.
        assertThat(VariableEncryption.DISABLED.fingerprint(canonical)).isEqualTo(SyncInvocationService.hashOf(request(SensitiveFixtures.CARD)));
    }
}
