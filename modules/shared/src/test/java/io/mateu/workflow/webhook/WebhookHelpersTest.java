package io.mateu.workflow.webhook;

import io.mateu.workflow.webhook.WebhookSignatureVerifier.WebhookVerificationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebhookHelpersTest {

    private static Function<String, String> headers(Map<String, String> map) {
        // case-insensitive lookup, like a servlet header map
        return name -> map.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    // ── provider parsing ────────────────────────────────────────────────────

    @Test
    void parsesProviderFromPathAndFallsBackToGeneric() {
        assertThat(WebhookProvider.fromPath("github")).isEqualTo(WebhookProvider.GITHUB);
        assertThat(WebhookProvider.fromPath("GitLab")).isEqualTo(WebhookProvider.GITLAB);
        assertThat(WebhookProvider.fromPath("bitbucket")).isEqualTo(WebhookProvider.BITBUCKET);
        assertThat(WebhookProvider.fromPath("whatever")).isEqualTo(WebhookProvider.GENERIC);
        assertThat(WebhookProvider.fromPath(null)).isEqualTo(WebhookProvider.GENERIC);
    }

    // ── signature verification ──────────────────────────────────────────────

    @Test
    void verifiesGithubHmacUsingTheDocumentedVector() {
        // GitHub's own documented example vector.
        var secret = "It's a Secret to Everybody";
        var body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        var good = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITHUB, secret,
                headers(Map.of("X-Hub-Signature-256", good)), body))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITHUB, secret,
                headers(Map.of("X-Hub-Signature-256", "sha256=deadbeef")), body))
                .isInstanceOf(WebhookVerificationException.class);

        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITHUB, secret,
                headers(Map.of()), body))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void blankSecretSkipsVerification() {
        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITHUB, "  ",
                headers(Map.of()), new byte[0])).doesNotThrowAnyException();
    }

    @Test
    void verifiesGitlabAndGenericTokens() {
        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITLAB, "s3cret",
                headers(Map.of("X-Gitlab-Token", "s3cret")), new byte[0])).doesNotThrowAnyException();
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.GITLAB, "s3cret",
                headers(Map.of("X-Gitlab-Token", "wrong")), new byte[0]))
                .isInstanceOf(WebhookVerificationException.class);

        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.GENERIC, "tok",
                headers(Map.of("X-Webhook-Token", "tok")), new byte[0])).doesNotThrowAnyException();
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.GENERIC, "tok",
                headers(Map.of()), new byte[0]))
                .isInstanceOf(WebhookVerificationException.class);
    }

    // ── payload parsing ─────────────────────────────────────────────────────

    @Test
    void parsesGithubPushPayload() {
        var body = ("""
                { "ref": "refs/heads/master",
                  "repository": { "clone_url": "https://github.com/org/defs.git",
                                  "ssh_url": "git@github.com:org/defs.git",
                                  "html_url": "https://github.com/org/defs" } }
                """).getBytes(StandardCharsets.UTF_8);
        var p = GitPushPayloadParser.parse(WebhookProvider.GITHUB, body);
        assertThat(p.branch()).isEqualTo("master");
        assertThat(p.repositoryUrls()).contains("https://github.com/org/defs.git", "git@github.com:org/defs.git");
    }

    @Test
    void parsesGitlabPushPayload() {
        var body = ("""
                { "ref": "refs/heads/main",
                  "project": { "git_http_url": "https://gitlab.com/org/defs.git",
                               "git_ssh_url": "git@gitlab.com:org/defs.git" } }
                """).getBytes(StandardCharsets.UTF_8);
        var p = GitPushPayloadParser.parse(WebhookProvider.GITLAB, body);
        assertThat(p.branch()).isEqualTo("main");
        assertThat(p.repositoryUrls()).contains("https://gitlab.com/org/defs.git");
    }

    @Test
    void parsesBitbucketPushPayload() {
        var body = ("""
                { "push": { "changes": [ { "new": { "type": "branch", "name": "master" } } ] },
                  "repository": { "links": { "html": { "href": "https://bitbucket.org/org/defs" },
                                             "clone": [ { "href": "https://bitbucket.org/org/defs.git" } ] } } }
                """).getBytes(StandardCharsets.UTF_8);
        var p = GitPushPayloadParser.parse(WebhookProvider.BITBUCKET, body);
        assertThat(p.branch()).isEqualTo("master");
        assertThat(p.repositoryUrls()).contains("https://bitbucket.org/org/defs.git");
    }

    @Test
    void garbageOrEmptyPayloadYieldsEmpty() {
        assertThat(GitPushPayloadParser.parse(WebhookProvider.GITHUB, new byte[0]).isEmpty()).isTrue();
        assertThat(GitPushPayloadParser.parse(WebhookProvider.GITHUB, "not json".getBytes()).isEmpty()).isTrue();
    }

    // ── url matching ────────────────────────────────────────────────────────

    @Test
    void matchesRepositoryUrlsAcrossTheUsualVariations() {
        assertThat(RepositoryUrlMatcher.sameRepository(
                "https://github.com/org/defs.git", "git@github.com:org/defs.git")).isTrue();
        assertThat(RepositoryUrlMatcher.sameRepository(
                "https://github.com/org/defs", "https://github.com/org/defs.git/")).isTrue();
        assertThat(RepositoryUrlMatcher.sameRepository(
                "https://user:tok@github.com/org/defs.git", "https://github.com/org/defs")).isTrue();
        assertThat(RepositoryUrlMatcher.sameRepository(
                "https://github.com/org/defs", "https://github.com/org/other")).isFalse();
    }

    // ── registry ────────────────────────────────────────────────────────────

    @Test
    void inMemoryRegistryRemembersIdsPerRepoAndNamespace() {
        var registry = new InMemoryImportedDefinitionsRegistry();
        registry.replace("workflow", "https://r/defs.git", java.util.Set.of("a", "b"));
        assertThat(registry.idsFor("workflow", "https://r/defs.git")).containsExactlyInAnyOrder("a", "b");
        // different namespace is isolated
        assertThat(registry.idsFor("form", "https://r/defs.git")).isEmpty();
        registry.replace("workflow", "https://r/defs.git", java.util.Set.of("b", "c"));
        assertThat(registry.idsFor("workflow", "https://r/defs.git")).containsExactlyInAnyOrder("b", "c");
    }
}
