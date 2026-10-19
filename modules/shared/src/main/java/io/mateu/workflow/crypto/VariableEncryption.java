package io.mateu.workflow.crypto;

import java.nio.charset.StandardCharsets;

/**
 * Seals and opens sensitive values — the one entry point every module uses (engine, forms engine,
 * workers), auto-configured from {@code workflow.encryption.*} by {@link VariableEncryptionAutoConfiguration}.
 *
 * <p>{@link #DISABLED} when no key is configured: {@link #seal} then refuses (the engine refuses to load
 * a definition that declares sensitive variables before it ever gets here) and {@link #open} passes
 * plain values through but fails on a sealed one, naming the missing key.
 */
public final class VariableEncryption {

    /** No codec: nothing is sealed. */
    public static final VariableEncryption DISABLED = new VariableEncryption(null);

    private final VariableCodec codec;

    public VariableEncryption(VariableCodec codec) {
        this.codec = codec;
    }

    /** Whether a codec is configured. */
    public boolean enabled() {
        return codec != null;
    }

    /** The key new values are sealed with; null when disabled. */
    public String activeKeyId() {
        return codec == null ? null : codec.activeKeyId();
    }

    /**
     * {@code plaintext} sealed with the active key. Null stays null; an already sealed value is returned
     * unchanged (sealing twice would only hide the value from a reader holding the keys).
     */
    public String seal(String plaintext) {
        if (plaintext == null || VariableEnvelope.isSealed(plaintext)) {
            return plaintext;
        }
        if (codec == null) {
            throw new EncryptionConfigurationException("A sensitive value cannot be sealed: no encryption key is"
                    + " configured (workflow.encryption.keys.<id>).");
        }
        var keyId = codec.activeKeyId();
        var payload = codec.encode(keyId, plaintext.getBytes(StandardCharsets.UTF_8), VariableEnvelope.associatedData(keyId));
        return new VariableEnvelope(keyId, payload).format();
    }

    /** {@code value} opened when it is sealed; returned unchanged otherwise. */
    public String open(String value) {
        if (!VariableEnvelope.isSealed(value)) {
            return value;
        }
        var envelope = VariableEnvelope.parse(value);
        if (codec == null) {
            throw new VariableDecryptionException("A value sealed with key '" + envelope.keyId()
                    + "' cannot be opened: no encryption key is configured (workflow.encryption.keys."
                    + envelope.keyId() + ").");
        }
        var plain = codec.decode(envelope.keyId(), envelope.payload(), VariableEnvelope.associatedData(envelope.keyId()));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** {@code value} opened when this can; returned as it is otherwise (disabled, or the key is missing). */
    public String openIfPossible(String value) {
        if (!VariableEnvelope.isSealed(value) || codec == null) {
            return value;
        }
        try {
            return open(value);
        } catch (VariableDecryptionException e) {
            return value;
        }
    }

    /** The fingerprint key under {@code keyId} (see {@link VariableCodec#fingerprintKey}); null when there is none. */
    public byte[] fingerprintKey(String keyId) {
        if (codec == null || keyId == null || !codec.knows(keyId)) {
            return null;
        }
        return codec.fingerprintKey(keyId);
    }

    /** A digest of {@code canonical} safe to store when it may embed sensitive values; see {@link Fingerprints}. */
    public String fingerprint(String canonical) {
        return Fingerprints.of(canonical, this);
    }

    /** Whether {@code stored} is {@link #fingerprint} of {@code canonical}, under the key it was made with. */
    public boolean fingerprintMatches(String stored, String canonical) {
        return Fingerprints.matches(stored, canonical, this);
    }

    /** Whether a sealed value names a key this can open. */
    public boolean canOpen(String value) {
        if (!VariableEnvelope.isSealed(value) || codec == null) {
            return false;
        }
        try {
            return codec.knows(VariableEnvelope.parse(value).keyId());
        } catch (VariableDecryptionException e) {
            return false;
        }
    }
}
