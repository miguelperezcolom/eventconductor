package io.mateu.workflow.e2e;

import io.mateu.workflow.e2e.support.E2eConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A process started the very moment the HTTP connector accepts requests — before any
 * {@code ApplicationRunner} has run and before the application reports ready — finds its classpath
 * definition imported (JPA persistence, where the classpath definitions are imported into the database
 * at startup): 201, not the 404 a start in that window used to get.
 *
 * <p>The request is sent from the {@link WebServerInitializedEvent} listener, which Spring Boot calls
 * synchronously right after the connector starts, inside the context's lifecycle start — the earliest
 * moment a real client could reach the engine.
 */
@SpringBootTest(classes = io.mateu.e2ejpa.durability.JpaE2eTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "workflow.mode=embedded",
                "workflow.persistence=jpa",
                "workflow.cron-enabled=false",
                "workflow.outbox-poll-interval-ms=50",
                "workflow.message-api.api-key=" + StartupImportRaceJpaE2eTest.API_KEY,
                "spring.datasource.url=jdbc:h2:mem:startup-race;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.flyway.enabled=false",
                "spring.main.web-application-type=servlet",
                "spring.autoconfigure.exclude=",
        })
@Import({E2eConfig.class, StartupImportRaceJpaE2eTest.FirstRequestConfig.class})
class StartupImportRaceJpaE2eTest {

    static final String API_KEY = "startup-race-key";

    /** Sends a start the moment the web server is up and keeps what it was answered. */
    static class FirstRequest implements ApplicationListener<WebServerInitializedEvent> {

        volatile HttpResponse<String> response;
        volatile Exception failure;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            var port = event.getWebServer().getPort();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/workflow/api/processes"))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", API_KEY)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"definitionId\": \"sequential-3\", \"businessKey\": \"startup-race-1\"}"))
                    .build();
            try (var http = HttpClient.newHttpClient()) {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                failure = e;
            }
        }
    }

    @TestConfiguration
    static class FirstRequestConfig {
        @Bean
        FirstRequest firstRequest() {
            return new FirstRequest();
        }
    }

    @Autowired FirstRequest firstRequest;

    @Test
    void aStartSentAsSoonAsTheConnectorAcceptsRequestsFindsItsDefinition() {
        assertThat(firstRequest.failure).isNull();
        assertThat(firstRequest.response).as("the listener ran when the web server started").isNotNull();
        assertThat(firstRequest.response.statusCode())
                .as("answered %s", firstRequest.response.body())
                .isEqualTo(201);
        assertThat(firstRequest.response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).startsWith("/workflow/api/processes/"));
    }
}
