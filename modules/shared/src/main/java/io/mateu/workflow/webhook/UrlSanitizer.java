package io.mateu.workflow.webhook;

import java.util.regex.Pattern;

/**
 * Utility to sanitize sensitive inline credentials from URLs (e.g., git push or clone URLs)
 * before logging them, preventing security exposure of user/password/tokens in standard logs.
 */
public final class UrlSanitizer {

    private static final Pattern CREDENTIALS_PATTERN = Pattern.compile("(?<=://)[^/]+:[^/]+(?=@)");

    private UrlSanitizer() {
    }

    /**
     * Sanitizes credentials from a URL.
     * <p>Example: {@code https://user:token@github.com/org/repo} becomes {@code https://******@github.com/org/repo}
     *
     * @param url the raw URL; can be null/empty
     * @return the sanitized URL, or null if input was null
     */
    public static String sanitize(String url) {
        if (url == null) {
            return null;
        }
        return CREDENTIALS_PATTERN.matcher(url).replaceAll("******");
    }
}
