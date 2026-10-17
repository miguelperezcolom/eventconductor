package io.mateu.workflow.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 over a webhook body, compared in constant time. */
public final class Signatures {

    private Signatures() {
    }

    /** The lower-case hex HMAC-SHA256 of {@code body} under {@code secret}. */
    public static String hmacSha256Hex(String secret, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body == null ? new byte[0] : body));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Whether {@code presented} (hex, an optional {@code sha256=} prefix) is the HMAC-SHA256 of
     * {@code body} under {@code secret}. Null or blank never matches.
     */
    public static boolean matches(String secret, byte[] body, String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        var hex = presented.trim();
        if (hex.regionMatches(true, 0, "sha256=", 0, 7)) {
            hex = hex.substring(7);
        }
        var expected = hmacSha256Hex(secret, body).getBytes(StandardCharsets.US_ASCII);
        var given = hex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }
}
