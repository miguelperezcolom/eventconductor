package io.mateu.workflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the pushed repository URL(s) and branch from a provider's webhook JSON body.
 * Best-effort: an unparseable or unfamiliar payload yields {@link GitPushPayload#EMPTY} so the
 * caller can fall back to reimporting every configured repository.
 */
public final class GitPushPayloadParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GitPushPayloadParser() {
    }

    public static GitPushPayload parse(WebhookProvider provider, byte[] body) {
        if (body == null || body.length == 0) {
            return GitPushPayload.EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return GitPushPayload.EMPTY;
        }
        if (root == null || root.isMissingNode() || !root.isObject()) {
            return GitPushPayload.EMPTY;
        }
        return switch (provider) {
            case GITHUB -> github(root);
            case GITLAB -> gitlab(root);
            case BITBUCKET -> bitbucket(root);
            case GENERIC -> generic(root);
        };
    }

    private static GitPushPayload github(JsonNode root) {
        var urls = new ArrayList<String>();
        var repo = root.path("repository");
        addText(urls, repo.path("clone_url"));
        addText(urls, repo.path("ssh_url"));
        addText(urls, repo.path("git_url"));
        addText(urls, repo.path("html_url"));
        return new GitPushPayload(urls, branchFromRef(text(root.path("ref"))));
    }

    private static GitPushPayload gitlab(JsonNode root) {
        var urls = new ArrayList<String>();
        var project = root.path("project");
        addText(urls, project.path("git_http_url"));
        addText(urls, project.path("git_ssh_url"));
        addText(urls, project.path("http_url"));
        addText(urls, project.path("web_url"));
        var repo = root.path("repository");
        addText(urls, repo.path("git_http_url"));
        addText(urls, repo.path("git_ssh_url"));
        addText(urls, repo.path("url"));
        addText(urls, repo.path("homepage"));
        return new GitPushPayload(urls, branchFromRef(text(root.path("ref"))));
    }

    private static GitPushPayload bitbucket(JsonNode root) {
        var urls = new ArrayList<String>();
        var repo = root.path("repository");
        addText(urls, repo.path("links").path("html").path("href"));
        var clones = repo.path("links").path("clone");
        if (clones.isArray()) {
            clones.forEach(c -> addText(urls, c.path("href")));
        }
        String branch = null;
        var changes = root.path("push").path("changes");
        if (changes.isArray() && !changes.isEmpty()) {
            branch = text(changes.get(0).path("new").path("name"));
        }
        return new GitPushPayload(urls, branch);
    }

    private static GitPushPayload generic(JsonNode root) {
        // Accept a minimal { "repository": "…url…", "branch": "…" } as well as the GitHub shape.
        var urls = new ArrayList<String>();
        var repo = root.path("repository");
        if (repo.isObject()) {
            addText(urls, repo.path("clone_url"));
            addText(urls, repo.path("ssh_url"));
            addText(urls, repo.path("url"));
            addText(urls, repo.path("html_url"));
        } else {
            addText(urls, repo);
        }
        addText(urls, root.path("repositoryUrl"));
        addText(urls, root.path("url"));
        String branch = text(root.path("branch"));
        if (branch == null) {
            branch = branchFromRef(text(root.path("ref")));
        }
        return new GitPushPayload(urls, branch);
    }

    private static void addText(List<String> list, JsonNode node) {
        var t = text(node);
        if (t != null && !list.contains(t)) {
            list.add(t);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        var s = node.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** "refs/heads/master" → "master"; a bare branch name passes through. */
    static String branchFromRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        var prefix = "refs/heads/";
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref;
    }
}
