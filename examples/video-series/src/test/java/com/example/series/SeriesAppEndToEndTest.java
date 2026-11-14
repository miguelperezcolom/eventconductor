package com.example.series;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app, as the series records it: the engine on H2, the real task handlers, the stand-ins over real HTTP,
 * the demo agent runtime, the webhook gateway and the MCP server — no test kit in between. What the kit tests
 * stub, this runs: an AGENT step answered by the demo runtime, a signed webhook starting a process through
 * events.yaml, a synchronous invocation answered by REPLY, and the taxi race over real HTTP.
 */
@SpringBootTest(classes = VideoSeriesApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=18096", "SERIES_DB_URL=jdbc:h2:mem:series-e2e;DB_CLOSE_DELAY=-1"})
class SeriesAppEndToEndTest {

    private static final String ENGINE = "http://localhost:18096";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(ENGINE + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start(String definition, String businessKey, String variables) throws Exception {
        var response = send("POST", "/workflow/api/processes",
                "{\"definitionId\": \"" + definition + "\", \"businessKey\": \"" + businessKey + "\", \"variables\": " + variables + "}");
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    /** The process holding the key, once it has finished (or the last state seen after 30 s). */
    private JsonNode finished(String businessKey) throws Exception {
        JsonNode process = null;
        var until = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(until)) {
            var list = JSON.readTree(send("GET", "/workflow/api/processes?businessKey=" + businessKey, null).body()).path("content");
            if (!list.isEmpty()) {
                process = JSON.readTree(send("GET", "/workflow/api/processes/" + list.get(0).path("id").asText(), null).body());
                if (!process.path("status").asText().matches("PENDING|RUNNING")) {
                    return process;
                }
            }
            Thread.sleep(200);
        }
        return process;
    }

    @Test
    void theAgentStepRunsAgainstTheDemoRuntime() throws Exception {
        start("special-request-agent", "E2E-AGENT-1",
                "{\"guest\": \"Erin\", \"room\": \"204\", \"specialRequest\": \"I use a wheelchair\"}");

        var process = finished("E2E-AGENT-1");

        assertThat(process.path("status").asText()).isEqualTo("COMPLETED");
        var preparation = JSON.readTree(process.path("variables").path("preparation").asText());
        assertThat(preparation.path("category").asText()).isEqualTo("accessibility");
        assertThat(preparation.path("needsStaff").asBoolean()).isTrue();
        assertThat(process.path("variables").has("alertedAt")).isTrue();
    }

    @Test
    void aSignedWebhookStartsTheOnboardingWhichPublishesItsEvent() throws Exception {
        var body = "{\"bookingId\": \"E2E-CM-1\", \"guest\": \"Alice\", \"checkIn\": \"2026-12-20\"}";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("series-demo-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(send("POST", "/webhooks/channel-manager", body, "X-Signature-256", "sha256=0000").statusCode())
                .isEqualTo(401);
        var accepted = send("POST", "/webhooks/channel-manager", body, "X-Signature-256", signature, "CE-Id", "e2e-cm-1");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);

        var process = finished("booking-onboarding-E2E-CM-1");
        assertThat(process.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(send("GET", "/fake/events", null).body()).contains("com.example.guest.welcomed").contains("E2E-CM-1");
    }

    @Test
    void aQuoteIsAnsweredSynchronouslyByTheReplyStep() throws Exception {
        var response = send("POST", "/workflow/api/definitions/room-quote/invocations",
                "{\"businessKey\": \"E2E-QUOTE-1\", \"variables\": {\"roomType\": \"suite\", \"nights\": \"3\"}}",
                "Idempotency-Key", "e2e-quote-1", "Prefer", "wait=10");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var envelope = JSON.readTree(response.body());
        assertThat(envelope.path("outcome").asText()).isEqualTo("REPLIED");
        assertThat(envelope.path("reply").path("price").asText()).isEqualTo("780");
        assertThat(envelope.path("reply").path("currency").asText()).isEqualTo("EUR");
        assertThat(envelope.path("processStatus").asText()).isEqualTo("RUNNING");   // it holds the quote on
    }

    @Test
    void theFasterTaxiWinsAndTheSlowerOneIsCancelledAtItsCompany() throws Exception {
        start("airport-transfer", "E2E-TRANSFER-1", "{\"guest\": \"Erin\", \"flight\": \"IB3456\"}");

        var process = finished("E2E-TRANSFER-1");
        assertThat(process.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(process.path("variables").path("taxi").asText()).isEqualTo("City Cabs");

        // The loser completes a second later and is undone as a cleanup: only City Cabs keeps a booking.
        var until = Instant.now().plusSeconds(15);
        String held = "";
        while (Instant.now().isBefore(until)) {
            held = send("GET", "/fake/taxis/bookings", null).body();
            if (held.contains("city-cabs") && !held.contains("airport-express")) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(held).contains("E2E-TRANSFER-1").contains("city-cabs").doesNotContain("airport-express");
    }

    @Test
    void theMetricsOfTheEngineAreScraped() throws Exception {
        start("special-request-agent", "E2E-AGENT-2", "{\"guest\": \"Frank\", \"room\": \"118\", \"specialRequest\": \"late\"}");
        finished("E2E-AGENT-2");

        var metrics = send("GET", "/actuator/prometheus", null).body();
        assertThat(metrics).contains("eventconductor_process_started_total")
                .contains("eventconductor_process_completed_total");
    }
}
