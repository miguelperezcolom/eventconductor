package io.mateu.workflow;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A task, from its link to its completion, through the forms UI's own wire protocol: open the
 * task's page, claim it, complete it.
 *
 * <p>Through both front doors of this pod — {@code /_forms} and {@code /_forms-admin} — because a
 * console that mounts only the administration UI still has tasks to answer, and the page used to be
 * "Not found." through both: when Mateu folded {@code @Route} into {@code @UI}, the task page became
 * an app of its own at the literal path {@code /forms/task/:_taskId}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskPageJourneyTest {

    static final String TASK_PAGE = "io.mateu.workflow.infra.in.ui.pages.Task";
    static final String FORMS_HOME = "io.mateu.workflow.infra.in.ui.FormsHome";

    @LocalServerPort
    int port;

    @Autowired
    FormRepository forms;

    @Autowired
    FormExecutionRepository executions;

    /** No broker in a test: the reply to the engine is the use case's business, tested on its own. */
    @MockitoBean
    StreamBridge streamBridge;

    final HttpClient http = HttpClient.newHttpClient();

    String taskId;

    @BeforeEach
    void aTaskWaitsForSomeone() {
        when(streamBridge.send(anyString(), any())).thenReturn(true);
        var formId = "confirm-" + UUID.randomUUID();
        forms.save(new Form(formId, "Confirm the reset", "Say yes", List.of(
                new Field("comment", "Comment", FieldDataType.string, FieldStereotype.regular, true, null))));
        taskId = UUID.randomUUID().toString();
        executions.save(FormExecution.builder()
                .id(taskId)
                .formId(formId)
                .processId("process-1")
                .stepId("confirm")
                .stepExecutionId("step-execution-1")
                .status(FormExecutionStatus.PENDING)
                .build());
    }

    @Test
    void theTaskPageOpensThroughTheOperationsFrontDoor() throws Exception {
        var page = navigate("/_forms", "/forms/task/" + taskId);

        assertThat(page).doesNotContain("Not found.");
        assertThat(page).contains("Confirm the reset").contains("Claim").contains("Complete");
    }

    @Test
    void theTaskPageOpensThroughTheAdministrationFrontDoor() throws Exception {
        var page = navigate("/_forms-admin", "/forms/task/" + taskId);

        assertThat(page).doesNotContain("Not found.");
        assertThat(page).contains("Confirm the reset");
    }

    @Test
    void theTasksListAnswersThroughTheAdministrationFrontDoorToo() throws Exception {
        // Where "Back to list" and completing a task lead: a console that mounts only the
        // administration UI must not land on "Not found." after answering a task.
        assertThat(navigate("/_forms-admin", "/forms/tasks")).doesNotContain("Not found.");
        assertThat(navigate("/_forms", "/forms/tasks")).doesNotContain("Not found.");
    }

    @Test
    void whoeverClaimsATaskIsTheOneWhoCompletesIt() throws Exception {
        action("/_forms", "claim", "ana", "{\"_taskId\":\"" + taskId + "\"}");

        // The caller, not a fixed name: Complete is enabled for the assignee, and a task claimed
        // in someone else's name was a task nobody could complete.
        assertThat(executions.findById(taskId).orElseThrow().userId()).isEqualTo("ana");

        // Somebody else's claim does not take it away, and their completion is refused.
        action("/_forms", "claim", "bob", "{\"_taskId\":\"" + taskId + "\"}");
        action("/_forms", "complete", "bob", "{\"_taskId\":\"" + taskId + "\",\"comment\":\"from bob\"}");
        var stillOpen = executions.findById(taskId).orElseThrow();
        assertThat(stillOpen.userId()).isEqualTo("ana");
        assertThat(stillOpen.status()).isEqualTo(FormExecutionStatus.PENDING);

        var answer = action("/_forms", "complete", "ana", "{\"_taskId\":\"" + taskId + "\",\"comment\":\"yes\"}");

        var done = executions.findById(taskId).orElseThrow();
        assertThat(done.status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(done.values()).anySatisfy(value -> {
            assertThat(value.name()).isEqualTo("comment");
            assertThat(value.value()).isEqualTo("yes");
        });
        // Back to the list, through the door the page was reached by.
        assertThat(answer).contains("/forms/tasks").contains("\"baseUrl\":\"/_forms\"");
    }

    @Test
    void onceClaimedClaimMakesWayForReleaseAndReleaseHandsTheTaskBack() throws Exception {
        var claimed = action("/_forms", "claim", "ana", "{\"_taskId\":\"" + taskId + "\"}");

        // To the one who claimed it: no Claim any more, a Release instead.
        assertThat(claimed).contains("\"actionId\":\"release\"").doesNotContain("\"actionId\":\"claim\"");
        assertThat(navigate("/_forms", "/forms/task/" + taskId, "ana"))
                .contains("Release").doesNotContain("\"actionId\":\"claim\"");
        // To anybody else: neither — it is ana's to hand back, not theirs to take.
        assertThat(navigate("/_forms", "/forms/task/" + taskId, "bob"))
                .doesNotContain("\"actionId\":\"release\"").doesNotContain("\"actionId\":\"claim\"");

        // Somebody else's release does not take it away from ana.
        action("/_forms", "release", "bob", "{\"_taskId\":\"" + taskId + "\"}");
        assertThat(executions.findById(taskId).orElseThrow().userId()).isEqualTo("ana");

        var released = action("/_forms", "release", "ana", "{\"_taskId\":\"" + taskId + "\"}");

        var task = executions.findById(taskId).orElseThrow();
        assertThat(task.userId()).isNull();
        assertThat(task.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(released).contains("\"actionId\":\"claim\"").doesNotContain("\"actionId\":\"release\"");

        // Anybody may claim it now.
        action("/_forms", "claim", "bob", "{\"_taskId\":\"" + taskId + "\"}");
        assertThat(executions.findById(taskId).orElseThrow().userId()).isEqualTo("bob");
    }

    @Test
    void completingThroughTheAdministrationFrontDoorReturnsThroughIt() throws Exception {
        action("/_forms-admin", "claim", "ana", "{\"_taskId\":\"" + taskId + "\"}");
        var answer = action("/_forms-admin", "complete", "ana",
                "{\"_taskId\":\"" + taskId + "\",\"comment\":\"yes\"}");

        assertThat(executions.findById(taskId).orElseThrow().status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(answer).contains("\"baseUrl\":\"/_forms-admin\"");
    }

    /** What a shell asks when it navigates to a route of this remote. */
    String navigate(String baseUrl, String route) throws Exception {
        return navigate(baseUrl, route, null);
    }

    /** The same, signed in as somebody. */
    String navigate(String baseUrl, String route, String user) throws Exception {
        return post(baseUrl, route, user, """
                {"serverSideType":"%s","appState":{},"componentState":{},"parameters":{},
                 "initiatorComponentId":"root","consumedRoute":"","route":"%s","actionId":""}
                """.formatted(FORMS_HOME, route));
    }

    /** What the task page sends when one of its buttons is pressed. */
    String action(String baseUrl, String actionId, String user, String componentState) throws Exception {
        var route = "/forms/task/" + taskId;
        return post(baseUrl, route, user, """
                {"serverSideType":"%s","appState":{},"componentState":%s,"parameters":{},
                 "initiatorComponentId":"task","consumedRoute":"","route":"%s","actionId":"%s"}
                """.formatted(TASK_PAGE, componentState, route, actionId));
    }

    String post(String baseUrl, String route, String user, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + baseUrl + "/mateu/v3/sync" + route))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            request.header("Authorization", "Bearer " + token(user));
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    /** A token as the gateway forwards it, already verified there: the page only reads who it is. */
    static String token(String user) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(("{\"sub\":\"" + user + "\",\"preferred_username\":\"" + user + "\"}")
                .getBytes(StandardCharsets.UTF_8))
                + ".";
    }
}
