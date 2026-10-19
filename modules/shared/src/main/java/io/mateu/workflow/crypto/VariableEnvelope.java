package io.mateu.workflow.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The text form of a sealed value: {@code ec:enc:v1:<keyId>:<base64url(codec output)>}.
 *
 * <p>Self-describing on purpose. Whoever reads a stored or transported value needs no policy to know
 * it is sealed and with which key — anything starting with {@link #PREFIX} is opened, anything else is
 * passed through — so a value sealed under a definition that no longer declares it still opens, and a
 * worker without the codec can tell a sealed value from a plain one. The header
 * ({@code ec:enc:v1:<keyId>}) is the AEAD's associated data: the key id cannot be swapped.
 */
public record VariableEnvelope(String keyId, byte[] payload) {

    /** What every sealed value starts with. */
    public static final String PREFIX = "ec:enc:v1:";

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    /** Whether {@code value} is a sealed value (its header, at least). */
    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** The header authenticated with the payload. */
    public static byte[] associatedData(String keyId) {
        return (PREFIX + keyId).getBytes(StandardCharsets.UTF_8);
    }

    /** Parses a sealed value. */
    public static VariableEnvelope parse(String value) {
        if (!isSealed(value)) {
            throw new VariableDecryptionException("Not a sealed value.");
        }
        var rest = value.substring(PREFIX.length());
        var colon = rest.indexOf(':');
        if (colon <= 0) {
            throw new VariableDecryptionException("A sealed value has no key id.");
        }
        var keyId = rest.substring(0, colon);
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new VariableDecryptionException("A sealed value names an invalid key id.");
        }
        try {
            return new VariableEnvelope(keyId, Base64.getUrlDecoder().decode(rest.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            throw new VariableDecryptionException("A value sealed with key '" + keyId + "' is not valid base64.");
        }
    }

    /** The text form. */
    public String format() {
        return PREFIX + keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }

    /** Fails on a key id that could not appear in an envelope. */
    static void requireValidKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new EncryptionConfigurationException("Encryption key id '" + keyId
                    + "' must be 1 to 64 letters, digits, '_', '.' or '-'.");
        }
    }
}
