package io.mateu.workflow.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Digests of data that may carry sensitive values — today the synchronous invocation's request
 * fingerprint, which a retry with the same idempotency key is compared against.
 *
 * <p>An unkeyed SHA-256 of a request made of a card number can be reversed by enumerating card numbers.
 * So, whenever a codec can provide a fingerprint key ({@link VariableCodec#fingerprintKey}), the digest is
 * an HMAC-SHA256 under a key derived from the active encryption key (HKDF, fixed label), written
 * {@code h1:<keyId>:<base64url>}. Verification uses the key the stored fingerprint names, so a rotation
 * does not break the idempotency of invocations still within their retention; a fingerprint stored before
 * keys existed (64 hex characters, plain SHA-256) is still verified as one.
 */
public final class Fingerprints {

    /** What every keyed fingerprint starts with. */
    public static final String PREFIX = "h1:";

    private Fingerprints() {
    }

    /** The fingerprint of {@code canonical}: keyed when {@code encryption} can key it, plain SHA-256 otherwise. */
    public static String of(String canonical, VariableEncryption encryption) {
        var keyId = encryption == null ? null : encryption.activeKeyId();
        var key = keyId == null ? null : encryption.fingerprintKey(keyId);
        if (key == null) {
            return HexFormat.of().formatHex(sha256(canonical));
        }
        return PREFIX + keyId + ":" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, canonical));
    }

    /** Whether {@code stored} is the fingerprint of {@code canonical}, under whichever key it was made with. */
    public static boolean matches(String stored, String canonical, VariableEncryption encryption) {
        if (stored == null) {
            return false;
        }
        if (!stored.startsWith(PREFIX)) {
            // Made before any key was configured (or by a codec without fingerprint keys).
            return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                    HexFormat.of().formatHex(sha256(canonical)).getBytes(StandardCharsets.UTF_8));
        }
        var rest = stored.substring(PREFIX.length());
        var colon = rest.lastIndexOf(':');
        if (colon <= 0 || encryption == null) {
            return false;
        }
        var key = encryption.fingerprintKey(rest.substring(0, colon));
        if (key == null) {
            return false;
        }
        byte[] expected;
        try {
            expected = java.util.Base64.getUrlDecoder().decode(rest.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, hmac(key, canonical));
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, String text) {
        return hmac(key, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869) with an all-zero salt: 32 bytes of key material for {@code label}. */
    static byte[] hkdf(byte[] inputKey, String label) {
        var prk = hmac(new byte[32], inputKey);
        var info = label.getBytes(StandardCharsets.UTF_8);
        var block = new byte[info.length + 1];
        System.arraycopy(info, 0, block, 0, info.length);
        block[info.length] = 1;
        return hmac(prk, block);
    }
}
