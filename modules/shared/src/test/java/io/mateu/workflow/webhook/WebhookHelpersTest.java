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

    @Test
    void verifiesBitbucketHmac() {
        // Bitbucket Server uses the same HMAC as GitHub, in X-Hub-Signature.
        var secret = "It's a Secret to Everybody";
        var body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        var good = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

        assertThatCode(() -> WebhookSignatureVerifier.verify(WebhookProvider.BITBUCKET, secret,
                headers(Map.of("X-Hub-Signature", good)), body)).doesNotThrowAnyException();
        // Malformed (non-hex) signature → rejected.
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.BITBUCKET, secret,
                headers(Map.of("X-Hub-Signature", "sha256=zz")), body))
                .isInstanceOf(WebhookVerificationException.class);
        // Missing header → rejected.
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(WebhookProvider.BITBUCKET, secret,
                headers(Map.of()), body)).isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void parsesGenericPayloads() {
        var stringForm = "{\"repository\":\"https://x/defs.git\",\"branch\":\"master\"}".getBytes(StandardCharsets.UTF_8);
        var p1 = GitPushPayloadParser.parse(WebhookProvider.GENERIC, stringForm);
        assertThat(p1.branch()).isEqualTo("master");
        assertThat(p1.repositoryUrls()).contains("https://x/defs.git");

        var objForm = "{\"repository\":{\"clone_url\":\"https://y/defs.git\"},\"ref\":\"refs/heads/dev\"}".getBytes(StandardCharsets.UTF_8);
        var p2 = GitPushPayloadParser.parse(WebhookProvider.GENERIC, objForm);
        assertThat(p2.branch()).isEqualTo("dev");
        assertThat(p2.repositoryUrls()).contains("https://y/defs.git");

        var urlField = "{\"repositoryUrl\":\"https://z/defs.git\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(GitPushPayloadParser.parse(WebhookProvider.GENERIC, urlField).repositoryUrls())
                .contains("https://z/defs.git");
    }

    @Test
    void collectsAlternateRepositoryUrlFields() {
        var gitlab = ("{\"ref\":\"refs/heads/main\",\"project\":{\"web_url\":\"https://gitlab.com/o/d\"},"
                + "\"repository\":{\"git_http_url\":\"https://gitlab.com/o/d.git\",\"homepage\":\"https://gitlab.com/o/d\"}}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(GitPushPayloadParser.parse(WebhookProvider.GITLAB, gitlab).repositoryUrls())
                .contains("https://gitlab.com/o/d.git");

        var github = "{\"ref\":\"refs/heads/x\",\"repository\":{\"git_url\":\"git://github.com/o/d.git\",\"html_url\":\"https://github.com/o/d\"}}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(GitPushPayloadParser.parse(WebhookProvider.GITHUB, github).repositoryUrls())
                .contains("git://github.com/o/d.git", "https://github.com/o/d");
    }

    @Test
    void payloadEmptinessAndNonObjectRoot() {
        assertThat(GitPushPayload.EMPTY.isEmpty()).isTrue();
        assertThat(new GitPushPayload(java.util.List.of(), "master").isEmpty()).isFalse();       // branch only
        assertThat(new GitPushPayload(java.util.List.of("https://x"), null).isEmpty()).isFalse(); // repo only
        // A JSON array (root is not an object) → EMPTY.
        assertThat(GitPushPayloadParser.parse(WebhookProvider.GITHUB, "[]".getBytes(StandardCharsets.UTF_8)).isEmpty()).isTrue();
    }

    @Test
    void normalizesWwwAndNullUrls() {
        assertThat(RepositoryUrlMatcher.sameRepository(
                "https://www.github.com/o/d.git", "https://github.com/o/d")).isTrue();
        assertThat(RepositoryUrlMatcher.sameRepository(null, "https://x")).isFalse();
        assertThat(RepositoryUrlMatcher.sameRepository("   ", "https://x")).isFalse();
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

    // ── url sanitization ───────────────────────────────────────────────────

    @Test
    void sanitizesSensitiveCredentialsFromGitUrls() {
        assertThat(UrlSanitizer.sanitize("https://github.com/org/repo.git"))
                .isEqualTo("https://github.com/org/repo.git");
        assertThat(UrlSanitizer.sanitize("https://username:password@github.com/org/repo.git"))
                .isEqualTo("https://******@github.com/org/repo.git");
        assertThat(UrlSanitizer.sanitize(null)).isNull();
    }

    /**
     * The shape a personal access token actually travels in. It carries no colon, so a pattern
     * written around {@code user:password} lets the one secret that matters straight through into
     * the log.
     */
    @Test
    void sanitizesATokenThatIsTheWholeUserinfo() {
        assertThat(UrlSanitizer.sanitize("https://NOT-A-REAL-TOKEN@github.com/org/repo.git"))
                .isEqualTo("https://******@github.com/org/repo.git");
        assertThat(UrlSanitizer.sanitize("https://x-access-token:NOT-A-REAL-TOKEN@github.com/org/repo.git"))
                .isEqualTo("https://******@github.com/org/repo.git");
    }

    /** An scp-style remote has no userinfo to hide: {@code git@} there is the ssh user. */
    @Test
    void leavesScpStyleRemotesAlone() {
        assertThat(UrlSanitizer.sanitize("git@github.com:org/repo.git"))
                .isEqualTo("git@github.com:org/repo.git");
    }
}
