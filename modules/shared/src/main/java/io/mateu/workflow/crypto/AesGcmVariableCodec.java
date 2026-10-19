package io.mateu.workflow.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default {@link VariableCodec}: AES-256-GCM with a fresh 96-bit random IV per value and a 128-bit
 * tag. The output is {@code iv ‖ ciphertext ‖ tag}.
 *
 * <p>Rotation: values are encrypted with the active key and decrypted with whichever configured key
 * they name, so adding a key, making it active and keeping the old one is all a rotation takes.
 */
public final class AesGcmVariableCodec implements VariableCodec {

    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public AesGcmVariableCodec(EncryptionKeySource source) {
        this(source.activeKeyId(), source.keys());
    }

    public AesGcmVariableCodec(String activeKeyId, Map<String, byte[]> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new EncryptionConfigurationException("No encryption key is configured (workflow.encryption.keys.<id>).");
        }
        var built = new LinkedHashMap<String, SecretKeySpec>();
        keys.forEach((id, material) -> {
            VariableEnvelope.requireValidKeyId(id);
            if (material == null || material.length != KEY_BYTES) {
                throw new EncryptionConfigurationException("Encryption key '" + id + "' must be " + KEY_BYTES
                        + " bytes (AES-256), base64-encoded; it is " + (material == null ? 0 : material.length) + " bytes.");
            }
            built.put(id, new SecretKeySpec(material.clone(), "AES"));
        });
        if (activeKeyId == null || activeKeyId.isBlank()) {
            if (built.size() != 1) {
                throw new EncryptionConfigurationException("Several encryption keys are configured ("
                        + String.join(", ", built.keySet()) + "): set workflow.encryption.active-key to the one that encrypts.");
            }
            activeKeyId = built.keySet().iterator().next();
        }
        if (!built.containsKey(activeKeyId)) {
            throw new EncryptionConfigurationException("workflow.encryption.active-key '" + activeKeyId
                    + "' is not one of the configured keys (" + String.join(", ", built.keySet()) + ").");
        }
        this.keys = Map.copyOf(built);
        this.activeKeyId = activeKeyId;
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public boolean knows(String keyId) {
        return keyId != null && keys.containsKey(keyId);
    }

    @Override
    public byte[] encode(String keyId, byte[] plaintext, byte[] associatedData) {
        var key = key(keyId);
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            var sealed = cipher.doFinal(plaintext);
            var out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed with key '" + keyId + "': " + e.getClass().getSimpleName());
        }
    }

    @Override
    public byte[] decode(String keyId, byte[] ciphertext, byte[] associatedData) {
        var key = key(keyId);
        if (ciphertext == null || ciphertext.length < IV_BYTES + TAG_BITS / 8) {
            throw new VariableDecryptionException("A value sealed with key '" + keyId + "' is truncated.");
        }
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES));
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new VariableDecryptionException("A value sealed with key '" + keyId
                    + "' failed authentication: it was tampered with, or sealed with a different key of that id.");
        } catch (GeneralSecurityException e) {
            throw new VariableDecryptionException("A value sealed with key '" + keyId + "' could not be decrypted: "
                    + e.getClass().getSimpleName());
        }
    }

    /** HKDF-SHA256 of the key with the fixed label {@value #FINGERPRINT_LABEL}: a separate key, the same rotation. */
    @Override
    public byte[] fingerprintKey(String keyId) {
        return Fingerprints.hkdf(key(keyId).getEncoded(), FINGERPRINT_LABEL);
    }

    static final String FINGERPRINT_LABEL = "eventconductor/fingerprint/v1";

    private SecretKeySpec key(String keyId) {
        var key = keyId == null ? null : keys.get(keyId);
        if (key == null) {
            throw new VariableDecryptionException("Encryption key '" + keyId + "' is not configured"
                    + " (workflow.encryption.keys." + keyId + "): keep every key that sealed a stored value.");
        }
        return key;
    }
}
