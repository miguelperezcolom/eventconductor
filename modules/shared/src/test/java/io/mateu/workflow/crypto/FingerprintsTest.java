package io.mateu.workflow.crypto;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The request fingerprint: keyed when keys exist, verifiable across a rotation, never a plain digest of a card. */
class FingerprintsTest {

    static final String REQUEST = "bk=\ncardNumber=4111111111111111\n";

    @Test
    void withKeysTheFingerprintIsAnHmacNotAGuessableDigest() {
        var encryption = AesGcmVariableCodecTest.encryption("k1", Map.of("k1", AesGcmVariableCodecTest.key()));
        var fingerprint = encryption.fingerprint(REQUEST);
        var plain = HexFormat.of().formatHex(Fingerprints.sha256(REQUEST));
        assertThat(fingerprint).startsWith("h1:k1:").doesNotContain(plain).hasSizeLessThanOrEqualTo(255);
        // Deterministic (it is compared), and specific to the request.
        assertThat(encryption.fingerprint(REQUEST)).isEqualTo(fingerprint);
        assertThat(encryption.fingerprintMatches(fingerprint, REQUEST)).isTrue();
        assertThat(encryption.fingerprintMatches(fingerprint, "bk=\ncardNumber=4000000000000002\n")).isFalse();
        // An attacker with another key (or the plain digest) cannot reproduce it.
        var other = AesGcmVariableCodecTest.encryption("k1", Map.of("k1", AesGcmVariableCodecTest.key()));
        assertThat(other.fingerprintMatches(fingerprint, REQUEST)).isFalse();
        assertThat(encryption.fingerprintMatches(plain, REQUEST))
                .as("a legacy plain digest still verifies").isTrue();
    }

    @Test
    void aRotationKeepsOldFingerprintsVerifiable() {
        var old = AesGcmVariableCodecTest.key();
        var before = AesGcmVariableCodecTest.encryption("2026-04", Map.of("2026-04", old));
        var stored = before.fingerprint(REQUEST);

        var keys = new LinkedHashMap<String, byte[]>();
        keys.put("2026-04", old);
        keys.put("2026-10", AesGcmVariableCodecTest.key());
        var after = AesGcmVariableCodecTest.encryption("2026-10", keys);
        assertThat(after.fingerprint(REQUEST)).startsWith("h1:2026-10:");
        assertThat(after.fingerprintMatches(stored, REQUEST)).isTrue();
        // Once the old key is gone the old fingerprint no longer verifies (the invocation would be refused).
        var retired = AesGcmVariableCodecTest.encryption("2026-10", Map.of("2026-10", keys.get("2026-10")));
        assertThat(retired.fingerprintMatches(stored, REQUEST)).isFalse();
    }

    @Test
    void withoutKeysItIsThePlainDigestAsBefore() {
        var plain = VariableEncryption.DISABLED.fingerprint(REQUEST);
        assertThat(plain).hasSize(64).isEqualTo(HexFormat.of().formatHex(Fingerprints.sha256(REQUEST)));
        assertThat(VariableEncryption.DISABLED.fingerprintMatches(plain, REQUEST)).isTrue();
        assertThat(VariableEncryption.DISABLED.fingerprintMatches("h1:k1:abc", REQUEST)).isFalse();
        assertThat(VariableEncryption.DISABLED.fingerprintMatches(null, REQUEST)).isFalse();
        var encryption = AesGcmVariableCodecTest.encryption("k1", Map.of("k1", AesGcmVariableCodecTest.key()));
        assertThat(encryption.fingerprintMatches("h1:nokey", REQUEST)).isFalse();
        assertThat(encryption.fingerprintMatches("h1:k1:!!!", REQUEST)).isFalse();
        assertThat(encryption.fingerprintKey("unknown")).isNull();
        // A codec without fingerprint keys (a KMS that never exposes material) falls back to the digest.
        VariableCodec kms = new VariableCodec() {
            public String activeKeyId() { return "kms"; }
            public boolean knows(String keyId) { return true; }
            public byte[] encode(String keyId, byte[] plaintext, byte[] aad) { return plaintext; }
            public byte[] decode(String keyId, byte[] ciphertext, byte[] aad) { return ciphertext; }
        };
        assertThat(new VariableEncryption(kms).fingerprint(REQUEST)).isEqualTo(plain);
    }

    @Test
    void hkdfDerivesASeparateKey() {
        var key = AesGcmVariableCodecTest.key();
        var derived = Fingerprints.hkdf(key, AesGcmVariableCodec.FINGERPRINT_LABEL);
        assertThat(derived).hasSize(32).isNotEqualTo(key);
        assertThat(Fingerprints.hkdf(key, "other")).isNotEqualTo(derived);
    }
}
