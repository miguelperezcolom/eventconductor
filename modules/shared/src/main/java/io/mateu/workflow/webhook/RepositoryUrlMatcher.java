package io.mateu.workflow.webhook;

/**
 * Decides whether two git URLs point to the same repository, tolerant of the usual
 * variations: https vs ssh (including scp-like {@code git@host:org/repo}), a trailing
 * {@code .git}, trailing slashes, a leading {@code www.}, embedded credentials, and case.
 */
public final class RepositoryUrlMatcher {

    private RepositoryUrlMatcher() {
    }

    public static boolean sameRepository(String a, String b) {
        var na = normalize(a);
        var nb = normalize(b);
        return na != null && na.equals(nb);
    }

    static String normalize(String url) {
        if (url == null) {
            return null;
        }
        var s = url.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("git@")) {
            // scp-like: git@github.com:org/repo.git → github.com/org/repo.git
            s = s.substring("git@".length()).replaceFirst(":", "/");
        } else {
            int scheme = s.indexOf("://");
            if (scheme >= 0) {
                s = s.substring(scheme + 3);
            }
            int at = s.indexOf('@'); // strip user:pass@
            if (at >= 0) {
                s = s.substring(at + 1);
            }
        }
        s = s.toLowerCase();
        if (s.startsWith("www.")) {
            s = s.substring(4);
        }
        // Trailing slashes first, then ".git", then any slash a ".git/" left behind — so
        // "org/defs", "org/defs.git" and "org/defs.git/" all normalise to the same value.
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }
}
