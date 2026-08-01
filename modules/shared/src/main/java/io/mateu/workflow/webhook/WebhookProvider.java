package io.mateu.workflow.webhook;

/**
 * A source-control provider that can deliver a git push/merge webhook. Selected from the
 * webhook endpoint path (e.g. {@code /workflow/webhooks/github}); unknown values fall back to
 * {@link #GENERIC}.
 */
public enum WebhookProvider {
    GITHUB, GITLAB, BITBUCKET, GENERIC;

    public static WebhookProvider fromPath(String value) {
        if (value == null) {
            return GENERIC;
        }
        return switch (value.trim().toLowerCase()) {
            case "github" -> GITHUB;
            case "gitlab" -> GITLAB;
            case "bitbucket" -> BITBUCKET;
            default -> GENERIC;
        };
    }
}
