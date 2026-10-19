package io.mateu.workflow.crypto;

import java.util.Map;

/**
 * Where the default AES-GCM codec gets its keys from — the hook for a key manager.
 *
 * <p>The default reads {@code workflow.encryption.keys.<id>} (base64 of 32 bytes) and
 * {@code workflow.encryption.active-key}. Declare a bean of this type to fetch them elsewhere instead —
 * data keys unwrapped by AWS KMS / GCP KMS / Azure Key Vault at startup, or read from a Vault KV path —
 * without the engine depending on any cloud SDK. It is read once, when the codec is built.
 *
 * <pre>{@code
 * @Bean
 * EncryptionKeySource vaultKeys(VaultTemplate vault) {
 *     var data = vault.read("secret/eventconductor/variable-keys").getData();
 *     return EncryptionKeySource.of((String) data.get("active"), Map.of(
 *             "2026-10", Base64.getDecoder().decode((String) data.get("2026-10")),
 *             "2026-04", Base64.getDecoder().decode((String) data.get("2026-04"))));
 * }
 * }</pre>
 */
public interface EncryptionKeySource {

    /** The keys by id: each 32 bytes (AES-256). Old keys stay here for as long as values use them. */
    Map<String, byte[]> keys();

    /** The id of the key new values are encrypted with; one of {@link #keys()}. */
    String activeKeyId();

    /** A fixed key source. */
    static EncryptionKeySource of(String activeKeyId, Map<String, byte[]> keys) {
        var copy = Map.copyOf(keys);
        return new EncryptionKeySource() {
            @Override
            public Map<String, byte[]> keys() {
                return copy;
            }

            @Override
            public String activeKeyId() {
                return activeKeyId;
            }
        };
    }
}
