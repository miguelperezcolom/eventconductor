package io.mateu.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the endpoint this app says it exposes actually answers.
 *
 * <p>`management.endpoints.web.exposure.include` listed `prometheus`, and the pom declared no
 * registry — and an endpoint with no registry behind it does not exist, so `/actuator/prometheus`
 * returned 404. Exposure is a permission, not a creation: naming an endpoint you have no
 * implementation for is accepted in silence.
 *
 * <p>Which is worse than not scraping at all. Anything pointed at these pods gets a target that is
 * permanently down, and a down target is an alert about the wrong thing.
 *
 * <p>Asserted over HTTP rather than by looking for a registry bean, because the bean is not the
 * promise: the promise is that a scrape gets metrics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusEndpointTest {

    @LocalServerPort
    int port;

    @Test
    void the_prometheus_endpoint_answers_with_metrics() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("a scrape of /actuator/prometheus").isEqualTo(200);
        // Not just a 200: a scrape that returns an empty body is a target that is up and useless.
        assertThat(response.body()).contains("jvm_");
    }
}
