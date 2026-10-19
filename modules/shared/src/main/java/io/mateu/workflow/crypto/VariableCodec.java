package io.mateu.workflow.crypto;

/**
 * The SPI that turns a sensitive variable's bytes into ciphertext and back — EventConductor's
 * counterpart of Temporal's Payload Codec.
 *
 * <p>The engine never calls it directly: {@link VariableEncryption} wraps every result in the
 * versioned envelope {@code ec:enc:v1:<keyId>:<base64url>} ({@link VariableEnvelope}) and hands the
 * envelope's header in as {@code associatedData}, so a codec only ever deals in bytes and key ids.
 *
 * <p>The default is {@link AesGcmVariableCodec} (AES-256-GCM, keys from
 * {@code workflow.encryption.keys.<id>}). Declare a bean of this type to replace it — for instance
 * one that calls a KMS's Encrypt/Decrypt per value, or a Vault transit engine. Implementations must be
 * thread-safe, must authenticate (a tampered ciphertext has to fail, not decrypt to garbage), and must
 * never put plaintext or key material in an exception message.
 */
public interface VariableCodec {

    /** The key new values are encrypted with. */
    String activeKeyId();

    /** Whether this codec can decrypt values encrypted under {@code keyId} (rotation keeps old keys). */
    boolean knows(String keyId);

    /** Encrypts {@code plaintext} under {@code keyId}, authenticating {@code associatedData} with it. */
    byte[] encode(String keyId, byte[] plaintext, byte[] associatedData);

    /**
     * Decrypts what {@link #encode} produced under {@code keyId}.
     *
     * @throws VariableDecryptionException when the key is unknown or the ciphertext (or its associated
     *         data) was tampered with
     */
    byte[] decode(String keyId, byte[] ciphertext, byte[] associatedData);

    /**
     * Key material for <b>keyed fingerprints</b> under {@code keyId} — an HMAC key derived from (never equal
     * to) the encryption key, so a fingerprint of a request that carries sensitive values (the synchronous
     * invocation's idempotency check) cannot be brute-forced from the stored digest. Null when this codec
     * cannot provide one (a KMS that never exposes key material): the engine then falls back to an
     * unkeyed SHA-256, so a KMS-backed codec should derive one (e.g. from a data key it holds).
     */
    default byte[] fingerprintKey(String keyId) {
        return null;
    }
}
