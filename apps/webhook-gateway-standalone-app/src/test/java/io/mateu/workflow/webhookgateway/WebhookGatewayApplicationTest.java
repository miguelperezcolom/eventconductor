package io.mateu.workflow.webhookgateway;

import io.mateu.workflow.webhook.Signatures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The app wires the gateway: a GitHub endpoint checks its signature before anything is published. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "workflow.webhooks.transport=rest",
        "workflow.webhooks.engine.url=http://127.0.0.1:1",
        "workflow.webhooks.endpoints.github.provider=github",
        "workflow.webhooks.endpoints.github.destination=github-events",
        "workflow.webhooks.endpoints.github.secret=s3cret",
})
class WebhookGatewayApplicationTest {

    @LocalServerPort int port;

    private HttpResponse<String> post(String endpoint, byte[] body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/webhooks/" + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theGitHubEndpointIsMappedAndRefusesABadSignature() throws Exception {
        var body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(post("github", body, "X-GitHub-Event", "ping", "X-Hub-Signature-256", "sha256=00").statusCode())
                .isEqualTo(401);
        assertThat(post("other", body).statusCode()).isEqualTo(404);
    }

    @Test
    void aValidDeliveryReachesThePublisherWhichReportsTheUnreachableEngine() throws Exception {
        var body = "{\"zen\": \"hi\"}".getBytes(StandardCharsets.UTF_8);
        var response = post("github", body, "X-GitHub-Event", "ping", "X-GitHub-Delivery", "d-1",
                "X-Hub-Signature-256", "sha256=" + Signatures.hmacSha256Hex("s3cret", body));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("Could not reach the engine");
    }
}
