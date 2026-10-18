package io.mateu.workflow.webhookgateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On a real server, every spelling of the webhook path that reaches the controller is held to the body
 * limit: Tomcat decodes and strips {@code ;parameters} before the mapping, so the filter must too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "workflow.webhooks.transport=rest",
        "workflow.webhooks.engine.url=http://127.0.0.1:1",
        "workflow.webhooks.max-body-bytes=64",
        "workflow.webhooks.endpoints.partner.provider=generic",
        "workflow.webhooks.endpoints.partner.destination=partner-events",
        "workflow.webhooks.endpoints.partner.type=com.partner.changed",
})
class WebhookBodyLimitServerTest {

    @LocalServerPort int port;

    @ParameterizedTest
    @ValueSource(strings = {"/webhooks/partner", "/webhooks;x=y/partner", "/%77ebhooks/partner",
            "/webhooks/partner;x=y", "/webhooks//partner"})
    void anOversizedBodyIs413HoweverThePathIsSpelled(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"a\":\"" + "x".repeat(200) + "\"}"))
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(path).isEqualTo(413);
    }
}
