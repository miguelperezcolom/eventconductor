package io.mateu.workflow.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Function;

/**
 * Authenticates an inbound git webhook per provider, using the single configured shared
 * secret. All comparisons are constant-time. When no secret is configured, verification is
 * skipped (unchanged from the original GitHub-only behaviour).
 *
 * <ul>
 *   <li>GitHub — HMAC-SHA256 of the body in {@code X-Hub-Signature-256} ({@code sha256=…}).</li>
 *   <li>Bitbucket (Server) — HMAC-SHA256 in {@code X-Hub-Signature}. (Bitbucket Cloud sends no
 *       signature; use it with no secret, or the {@code generic} endpoint with a token.)</li>
 *   <li>GitLab — the secret token echoed verbatim in {@code X-Gitlab-Token}.</li>
 *   <li>Generic — a shared token in {@code X-Webhook-Token}.</li>
 * </ul>
 */
public final class WebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private WebhookSignatureVerifier() {
    }

    /** Thrown when a webhook cannot be authenticated. Callers map it to HTTP 401. */
    public static class WebhookVerificationException extends RuntimeException {
        public WebhookVerificationException(String message) {
            super(message);
        }
    }

    /**
     * @param provider which provider sent the webhook
     * @param secret   the configured shared secret; blank/null skips verification
     * @param headers  case-insensitive header lookup (name → value, null if absent)
     * @param body     the raw request body
     * @throws WebhookVerificationException if the request cannot be authenticated
     */
    public static void verify(WebhookProvider provider, String secret,
                              Function<String, String> headers, byte[] body) {
        if (secret == null || secret.isBlank()) {
            return;
        }
        switch (provider) {
            case GITHUB -> verifyHmac(headers.apply("X-Hub-Signature-256"), secret, body);
            case BITBUCKET -> verifyHmac(headers.apply("X-Hub-Signature"), secret, body);
            case GITLAB -> verifyToken(headers.apply("X-Gitlab-Token"), secret);
            case GENERIC -> verifyToken(headers.apply("X-Webhook-Token"), secret);
        }
    }

    private static void verifyHmac(String signatureHeader, String secret, byte[] body) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            throw new WebhookVerificationException("Missing or malformed HMAC signature header");
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signatureHeader.substring("sha256=".length()));
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException("Invalid webhook signature");
        }
        byte[] expected = HexFormat.of().parseHex(hmacSha256Hex(secret, body));
        if (!MessageDigest.isEqual(received, expected)) {
            throw new WebhookVerificationException("Invalid webhook signature");
        }
    }

    private static void verifyToken(String token, String secret) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookVerificationException("Invalid or missing webhook token");
        }
    }

    private static String hmacSha256Hex(String secret, byte[] data) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}
