package io.mateu.workflow.webhook;

import java.util.regex.Pattern;

/**
 * Utility to sanitize sensitive inline credentials from URLs (e.g., git push or clone URLs)
 * before logging them, preventing security exposure of user/password/tokens in standard logs.
 */
public final class UrlSanitizer {

    /**
     * The whole userinfo component, whatever shape it takes. Matching only {@code user:password}
     * would leave the commonest form of secret in the clear: a GitHub or GitLab token is embedded
     * as {@code https://ghp_xxx@host/…}, with no colon anywhere in it.
     *
     * <p>Anchored on {@code ://} so an scp-style remote — {@code git@github.com:org/repo.git} — is
     * left alone: there the {@code git@} is the ssh user, not a credential.
     */
    private static final Pattern CREDENTIALS_PATTERN = Pattern.compile("(?<=://)[^/@]+(?=@)");

    private UrlSanitizer() {
    }

    /**
     * Sanitizes credentials from a URL.
     * <p>Example: {@code https://user:token@github.com/org/repo} becomes {@code https://******@github.com/org/repo},
     * and so does the token-only {@code https://token@github.com/org/repo}.
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
