package io.mateu.formse2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A USER_TASK with a form, end to end, in one application that embeds both the workflow engine and
 * the forms engine — no broker. It used not to start at all: the forms engine published through a
 * {@code StreamBridge}, and embedded mode switches Spring Cloud Stream off. And had it started, the
 * {@code complete-form} task went to the application's executor, not to the forms engine.
 *
 * <p>The task shows up in the inbox of the forms API, is fetched, claimed and completed there, the
 * submitted values reach the process and the process finishes; the application's executor never sees
 * the task, and the task's events reach an {@code @EventListener}.
 */
public abstract class AbstractEmbeddedFormsJourney {

    protected static final Duration TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort
    int port;

    @Autowired
    FormRepository forms;

    @Autowired
    ProcessRepository processes;

    @Autowired
    ProcessUpstreamEventUseCase upstream;

    @Autowired
    RecordingTaskExecutor executor;

    @Autowired
    TaskEventsListener taskEvents;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @Test
    void theFormIsReadFromTheConfiguredDirectory() {
        // forms.directory-import, with the workflow engine in the same application: the engine has a
        // workflow.directory-import of its own, and its properties class used to shadow the forms one.
        var form = forms.findById("approval-form").orElseThrow();
        assertThat(form.name()).isEqualTo("Approve the booking");
        assertThat(form.fields()).extracting(Field::id).containsExactly("decision");
    }

    @Test
    void aManagerFindsTheTaskInTheInboxSubmitsTheFormAndTheProcessCarriesOn() throws Exception {
        var businessKey = "booking-" + UUID.randomUUID();
        upstream.handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested("approval", businessKey, List.of(new Variable("amount", "1500")))));
        var processId = await().atMost(TIMEOUT)
                .until(() -> processes.findByBusinessKey(businessKey).map(p -> p.id()).orElse(null), id -> id != null);

        // The task is in the inbox of a member of the group, through the forms API.
        var inbox = await().atMost(TIMEOUT).until(() -> get("/forms/api/tasks?processId=" + processId, "mia", "managers"),
                list -> list.get("total").asInt() == 1);
        var taskId = inbox.get("items").get(0).get("id").asText();
        assertThat(inbox.get("items").get(0).get("formId").asText()).isEqualTo("approval-form");

        var task = get("/forms/api/tasks/" + taskId, "mia", "managers");
        assertThat(task.get("formId").asText()).isEqualTo("approval-form");
        assertThat(task.get("stepId").asText()).isEqualTo("approve");

        var claimed = call("POST", "/forms/api/tasks/" + taskId + "/claim", "mia", null, "managers");
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        var completed = call("POST", "/forms/api/tasks/" + taskId + "/complete", "mia",
                "{\"values\": {\"decision\": \"APPROVE\"}}", "managers");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var process = processes.findByBusinessKey(businessKey).orElseThrow();
            assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
            assertThat(process.getVariables()).anySatisfy(variable -> {
                assertThat(variable.name()).isEqualTo("decision");
                assertThat(variable.value()).isEqualTo("APPROVE");
            });
        });

        // The forms engine took the task: the application's own worker never saw it.
        assertThat(executor.received).noneMatch(request -> processId.equals(request.processId()));
        // And told the application about it, in process.
        assertThat(taskEvents.received).filteredOn(changed -> taskId.equals(changed.taskId()))
                .extracting(HumanTaskChanged::event)
                .contains(HumanTaskChanged.CREATED, HumanTaskChanged.COMPLETED);
    }

    JsonNode get(String path, String user, String... roles) throws Exception {
        var response = call("GET", path, user, null, roles);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    HttpResponse<String> call(String method, String path, String user, String body, String... roles) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            request.header("Authorization", "Bearer " + token(user, roles));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A token as a gateway forwards it, already verified there, with the caller's roles (their groups). */
    static String token(String user, String... roles) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var roleList = new StringBuilder();
        for (var role : roles) {
            roleList.append(roleList.isEmpty() ? "" : ",").append('"').append(role).append('"');
        }
        var claims = "{\"sub\":\"" + user + "\",\"preferred_username\":\"" + user + "\","
                + "\"realm_access\":{\"roles\":[" + roleList + "]}}";
        return encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    /** What an application listening to task events receives. */
    public static class TaskEventsListener {

        public final List<HumanTaskChanged> received = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(HumanTaskChanged changed) {
            received.add(changed);
        }
    }
}
