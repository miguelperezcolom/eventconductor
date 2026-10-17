package io.mateu.workflow.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubWebhookProviderTest {

    static final String SECRET = "s3cret";
    static final byte[] PR = """
            {"action": "opened", "number": 7, "repository": {"full_name": "acme/shop"}, "sender": {"login": "alice"}}
            """.getBytes(StandardCharsets.UTF_8);

    final GitHubWebhookProvider provider = new GitHubWebhookProvider();

    static WebhookGatewayProperties.Endpoint endpoint(String secret) {
        var endpoint = new WebhookGatewayProperties.Endpoint();
        endpoint.setProvider(WebhookGatewayProperties.Provider.GITHUB);
        endpoint.setDestination("github-events");
        endpoint.setSecret(secret);
        return endpoint;
    }

    static Map<String, String> headers(byte[] body, String event, String delivery, String secret) {
        var headers = new HashMap<String, String>();
        headers.put("x-github-event", event);
        if (delivery != null) {
            headers.put("x-github-delivery", delivery);
        }
        if (secret != null) {
            headers.put("x-hub-signature-256", "sha256=" + Signatures.hmacSha256Hex(secret, body));
        }
        return headers;
    }

    @Test
    void aSignedDeliveryBecomesACloudEventTypedByEventAndAction() {
        var event = provider.read("github", endpoint(SECRET), headers(PR, "pull_request", "d-1", SECRET), PR);

        assertThat(event.id()).isEqualTo("d-1");
        assertThat(event.source()).isEqualTo("github");
        assertThat(event.type()).isEqualTo("com.github.pull_request.opened");
        assertThat(event.subject()).isEqualTo("acme/shop");
        assertThat(event.key()).isEqualTo("acme/shop");
        assertThat(event.dataContentType()).isEqualTo("application/json");
        assertThat(event.data()).contains("\"number\": 7");
        assertThat(event.extensions()).containsEntry("githubevent", "pull_request").containsEntry("githubaction", "opened")
                .containsEntry("repository", "acme/shop").containsEntry("sender", "alice");
        assertThat(event.time()).isNotBlank();
    }

    @Test
    void anEventWithoutActionAndAConfiguredSourceAndPrefix() {
        var endpoint = endpoint(SECRET);
        endpoint.setSource("github.com/acme");
        endpoint.setTypePrefix("gh.");
        var push = "{\"ref\": \"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);

        var event = provider.read("github", endpoint, headers(push, "push", null, SECRET), push);

        assertThat(event.type()).isEqualTo("gh.push");
        assertThat(event.source()).isEqualTo("github.com/acme");
        assertThat(event.subject()).isNull();
        assertThat(event.id()).isNotBlank();   // no delivery id: a fresh one
        assertThat(event.extensions()).containsOnlyKeys("githubevent");
    }

    @Test
    void aBadOrMissingSignatureIs401AndAMissingSecretIs500() {
        assertThatThrownBy(() -> provider.read("github", endpoint(SECRET), headers(PR, "push", "d", "wrong"), PR))
                .isInstanceOf(WebhookRejectedException.class)
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(401));
        assertThatThrownBy(() -> provider.read("github", endpoint(SECRET), headers(PR, "push", "d", null), PR))
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(401));
        assertThatThrownBy(() -> provider.read("github", endpoint(null), headers(PR, "push", "d", SECRET), PR))
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(500));
    }

    @Test
    void anUnreadableDeliveryIs400() {
        var headers = headers(PR, "push", "d", SECRET);
        headers.remove("x-github-event");
        assertThatThrownBy(() -> provider.read("github", endpoint(SECRET), headers, PR))
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(400));

        var notJson = "nope".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> provider.read("github", endpoint(SECRET), headers(notJson, "push", "d", SECRET), notJson))
                .satisfies(e -> assertThat(((WebhookRejectedException) e).status()).isEqualTo(400));
    }

    @Test
    void aDeliveryWithoutIdOrBodyStillBecomesAnEventWithAFreshId() {
        var empty = new byte[0];
        var event = provider.read("github", endpoint(SECRET), headers(empty, "ping", null, SECRET), empty);

        assertThat(event.id()).isNotBlank();
        assertThat(event.type()).isEqualTo("com.github.ping");
        assertThat(event.subject()).isNull();
        assertThat(event.extensions()).containsOnlyKeys("githubevent");
    }

    @Test
    void aRepositoryOrSenderThatIsNotAnObjectIsIgnored() {
        var body = """
                {"repository": "acme/shop", "sender": {"login": {"nested": true}}, "action": null}
                """.getBytes(StandardCharsets.UTF_8);
        var event = provider.read("github", endpoint(SECRET), headers(body, "push", "d-9", SECRET), body);

        assertThat(event.type()).isEqualTo("com.github.push");
        assertThat(event.subject()).isNull();
        assertThat(event.extensions()).containsOnlyKeys("githubevent");
    }

    @Test
    void withoutATypePrefixTheTypeIsTheBareEventName() {
        var endpoint = endpoint(SECRET);
        endpoint.setTypePrefix(null);
        var event = provider.read("github", endpoint, headers(PR, "pull_request", "d-1", SECRET), PR);
        assertThat(event.type()).isEqualTo("pull_request.opened");
    }
}
