package io.mateu.workflow.crypto;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmVariableCodecTest {

    static byte[] key() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    static VariableEncryption encryption(String active, Map<String, byte[]> keys) {
        return new VariableEncryption(new AesGcmVariableCodec(active, keys));
    }

    @Test
    void aValueRoundTripsThroughItsEnvelope() {
        var encryption = encryption("k1", Map.of("k1", key()));
        var sealed = encryption.seal("4111 1111 1111 1111 — ñ");
        assertThat(sealed).startsWith("ec:enc:v1:k1:").doesNotContain("4111");
        assertThat(VariableEnvelope.isSealed(sealed)).isTrue();
        assertThat(encryption.open(sealed)).isEqualTo("4111 1111 1111 1111 — ñ");
        // A fresh IV per value: the same plaintext never seals the same way twice.
        assertThat(encryption.seal("same")).isNotEqualTo(encryption.seal("same"));
        // Empty and null.
        assertThat(encryption.open(encryption.seal(""))).isEmpty();
        assertThat(encryption.seal(null)).isNull();
        // Plain values pass through, and a sealed value is not sealed twice.
        assertThat(encryption.open("plain")).isEqualTo("plain");
        assertThat(encryption.seal(sealed)).isSameAs(sealed);
        assertThat(encryption.canOpen(sealed)).isTrue();
        assertThat(encryption.canOpen("plain")).isFalse();
        assertThat(encryption.activeKeyId()).isEqualTo("k1");
    }

    @Test
    void aTamperedValueFailsAuthenticationInsteadOfDecryptingToGarbage() {
        var encryption = encryption("k1", Map.of("k1", key()));
        var sealed = encryption.seal("secret");
        var envelope = VariableEnvelope.parse(sealed);
        var payload = envelope.payload().clone();
        payload[payload.length - 1] ^= 1;
        var tampered = new VariableEnvelope("k1", payload).format();
        assertThatThrownBy(() -> encryption.open(tampered))
                .isInstanceOf(VariableDecryptionException.class)
                .hasMessageContaining("failed authentication")
                .hasMessageNotContaining("secret");
        // The key id is authenticated with the value: relabelling it under another key fails too.
        var twoKeys = encryption("k1", Map.of("k1", key(), "k2", key()));
        var relabelled = new VariableEnvelope("k2", VariableEnvelope.parse(twoKeys.seal("x")).payload()).format();
        assertThatThrownBy(() -> twoKeys.open(relabelled)).isInstanceOf(VariableDecryptionException.class);
        assertThatThrownBy(() -> encryption.open("ec:enc:v1:k1:" + "A".repeat(4)))
                .isInstanceOf(VariableDecryptionException.class).hasMessageContaining("truncated");
    }

    @Test
    void rotationSealsWithTheActiveKeyAndOpensWithAnyConfiguredOne() {
        var old = key();
        var current = key();
        var before = encryption("2026-04", Map.of("2026-04", old));
        var sealedBefore = before.seal("pan-1");

        var keys = new LinkedHashMap<String, byte[]>();
        keys.put("2026-04", old);
        keys.put("2026-10", current);
        var after = encryption("2026-10", keys);
        assertThat(after.open(sealedBefore)).isEqualTo("pan-1");
        assertThat(after.seal("pan-2")).startsWith("ec:enc:v1:2026-10:");

        var oldKeyRetired = encryption("2026-10", Map.of("2026-10", current));
        assertThatThrownBy(() -> oldKeyRetired.open(sealedBefore))
                .isInstanceOf(VariableDecryptionException.class)
                .hasMessageContaining("'2026-04' is not configured");
    }

    @Test
    void unusableConfigurationIsRefusedNamingTheKeyNotItsValue() {
        assertThatThrownBy(() -> new AesGcmVariableCodec("k", Map.of("k", new byte[16])))
                .isInstanceOf(EncryptionConfigurationException.class).hasMessageContaining("must be 32 bytes");
        assertThatThrownBy(() -> new AesGcmVariableCodec("nope", Map.of("k", key())))
                .isInstanceOf(EncryptionConfigurationException.class).hasMessageContaining("'nope' is not one of");
        assertThatThrownBy(() -> new AesGcmVariableCodec(null, Map.of("a", key(), "b", key())))
                .isInstanceOf(EncryptionConfigurationException.class).hasMessageContaining("active-key");
        assertThatThrownBy(() -> new AesGcmVariableCodec(null, Map.of()))
                .isInstanceOf(EncryptionConfigurationException.class);
        assertThatThrownBy(() -> new AesGcmVariableCodec(null, Map.of("bad:id", key())))
                .isInstanceOf(EncryptionConfigurationException.class).hasMessageContaining("key id");
        // A single key needs no active-key.
        assertThat(new AesGcmVariableCodec(null, Map.of("only", key())).activeKeyId()).isEqualTo("only");
    }

    @Test
    void disabledEncryptionRefusesToSealAndCannotOpen() {
        assertThat(VariableEncryption.DISABLED.enabled()).isFalse();
        assertThatThrownBy(() -> VariableEncryption.DISABLED.seal("x"))
                .isInstanceOf(EncryptionConfigurationException.class);
        var sealed = encryption("k1", Map.of("k1", key())).seal("x");
        assertThatThrownBy(() -> VariableEncryption.DISABLED.open(sealed))
                .isInstanceOf(VariableDecryptionException.class).hasMessageContaining("no encryption key");
        assertThat(VariableEncryption.DISABLED.openIfPossible(sealed)).isEqualTo(sealed);
        assertThat(VariableEncryption.DISABLED.open("plain")).isEqualTo("plain");
        assertThat(VariableEncryption.DISABLED.canOpen(sealed)).isFalse();
    }

    @Test
    void malformedEnvelopesAreRefused() {
        assertThatThrownBy(() -> VariableEnvelope.parse("plain")).isInstanceOf(VariableDecryptionException.class);
        assertThatThrownBy(() -> VariableEnvelope.parse("ec:enc:v1:nokey")).isInstanceOf(VariableDecryptionException.class);
        assertThatThrownBy(() -> VariableEnvelope.parse("ec:enc:v1:bad key:AAAA")).isInstanceOf(VariableDecryptionException.class);
        assertThatThrownBy(() -> VariableEnvelope.parse("ec:enc:v1:k:@@@")).isInstanceOf(VariableDecryptionException.class)
                .hasMessageContaining("base64");
        var encryption = encryption("k1", Map.of("k1", key()));
        assertThat(encryption.openIfPossible("ec:enc:v1:k1:@@@")).isEqualTo("ec:enc:v1:k1:@@@");
        assertThat(encryption.canOpen("ec:enc:v1:zz:AAAA")).isFalse();
    }

    @Test
    void propertiesBuildTheKeySource() {
        var properties = new EncryptionProperties();
        assertThat(properties.configured()).isFalse();
        var keys = new LinkedHashMap<String, String>();
        keys.put("a", Base64.getEncoder().encodeToString(key()));
        keys.put("empty", " ");
        properties.setKeys(keys);
        properties.setActiveKey(" a ");
        assertThat(properties.configured()).isTrue();
        var source = properties.toKeySource();
        assertThat(source.activeKeyId()).isEqualTo("a");
        assertThat(source.keys()).containsOnlyKeys("a");
        properties.setKeys(Map.of("b", "not base64!"));
        assertThatThrownBy(properties::toKeySource).hasMessageContaining("workflow.encryption.keys.b is not valid base64")
                .hasMessageNotContaining("not base64!");
        properties.setKeys(null);
        assertThat(properties.getKeys()).isEmpty();
    }
}
