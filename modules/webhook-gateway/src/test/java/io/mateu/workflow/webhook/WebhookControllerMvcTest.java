package io.mateu.workflow.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The endpoint, through the dispatcher, over the real providers and a publisher that remembers. */
class WebhookControllerMvcTest {

    static final String SECRET = "s3cret";
    static final byte[] PUSH = "{\"ref\": \"refs/heads/main\", \"repository\": {\"full_name\": \"acme/shop\"}}"
            .getBytes(StandardCharsets.UTF_8);

    final List<String> published = new ArrayList<>();
    final List<String> counted = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var properties = new WebhookGatewayProperties();
        var github = new WebhookGatewayProperties.Endpoint();
        github.setProvider(WebhookGatewayProperties.Provider.GITHUB);
        github.setDestination("github-events");
        github.setSecret(SECRET);
        properties.getEndpoints().put("github", github);
        var broken = new WebhookGatewayProperties.Endpoint();
        broken.setType("t");
        properties.getEndpoints().put("broken", broken);
        var providers = new WebhookGatewayAutoConfiguration().webhookProviders();
        WebhookPublisher publisher = (destination, event) -> {
            if ("github-events".equals(destination)) {
                published.add(destination + " " + event.type() + " " + event.id());
            } else {
                throw new WebhookRejectedException(502, "down");
            }
        };
        var controller = new WebhookController(properties, providers, publisher, (endpoint, outcome) -> counted.add(endpoint + ":" + outcome));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void aSignedGitHubDeliveryIsPublishedAndAnswered202() throws Exception {
        mvc.perform(post("/webhooks/github").content(PUSH).contentType("application/json")
                        .header("X-GitHub-Event", "push").header("X-GitHub-Delivery", "d-1")
                        .header("X-Hub-Signature-256", "sha256=" + Signatures.hmacSha256Hex(SECRET, PUSH)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.destination").value("github-events"))
                .andExpect(jsonPath("$.id").value("d-1"))
                .andExpect(jsonPath("$.type").value("com.github.push"));

        assertThat(published).containsExactly("github-events com.github.push d-1");
        assertThat(counted).containsExactly("github:published");
    }

    @Test
    void aBadSignatureIs401AnUnknownEndpoint404AndAMisconfiguredOne500() throws Exception {
        mvc.perform(post("/webhooks/github").content(PUSH).header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", "sha256=0000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("X-Hub-Signature-256")));
        mvc.perform(post("/webhooks/nope").content(PUSH)).andExpect(status().isNotFound());
        mvc.perform(post("/webhooks/broken").content(PUSH)).andExpect(status().isInternalServerError());

        assertThat(published).isEmpty();
        assertThat(counted).containsExactly("github:unauthorized", "nope:unknown", "broken:error");
    }

    @Test
    void aPublishFailureIsReportedSoTheSenderMayRedeliver() throws Exception {
        var properties = new WebhookGatewayProperties();
        var partner = new WebhookGatewayProperties.Endpoint();
        partner.setDestination("partner-events");
        partner.setType("com.partner.ping");
        properties.getEndpoints().put("partner", partner);
        var controller = new WebhookController(properties, new WebhookGatewayAutoConfiguration().webhookProviders(),
                (d, e) -> { throw new WebhookRejectedException(502, "down"); }, null);

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/webhooks/partner").content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("down"));
    }

    @Test
    void headersAreReadByLowerCaseName() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-GitHub-Event", "push");
        headers.add("X-GitHub-Event", "ignored");
        assertThat(WebhookController.lowerCase(headers)).containsExactly(Map.entry("x-github-event", "push"));
        assertThat(WebhookController.lowerCase(null)).isEmpty();
    }

    @Test
    void eachOutcomeIsCountedSoRefusalsAreVisible() throws Exception {
        // Signed, but no X-GitHub-Event: the provider cannot read it — a 400, counted as rejected.
        mvc.perform(post("/webhooks/github").content(PUSH).contentType("application/json")
                        .header("X-Hub-Signature-256", "sha256=" + Signatures.hmacSha256Hex(SECRET, PUSH)))
                .andExpect(status().isBadRequest());
        // A bad signature: 401, counted as unauthorized.
        mvc.perform(post("/webhooks/github").content(PUSH).contentType("application/json")
                        .header("X-GitHub-Event", "push").header("X-Hub-Signature-256", "sha256=00"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/nope").content(PUSH)).andExpect(status().isNotFound());

        assertThat(counted).containsExactly("github:rejected", "github:unauthorized", "nope:unknown");
        assertThat(published).isEmpty();
    }

    @Test
    void headersWithoutValuesAreLeftOut() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.put("X-Empty", List.of());
        headers.add("X-Full", "v");
        assertThat(WebhookController.lowerCase(headers)).containsOnlyKeys("x-full");
        assertThat(WebhookController.lowerCase(null)).isEmpty();
    }
}
