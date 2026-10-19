package io.mateu.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task the engine assigned to a group, end to end through this app: created as the engine's
 * USER_TASK request leaves it (the rendered assignment in {@code __task}), listed in the inbox of a
 * member of the group and of nobody else, claimed and completed over {@code /forms/api/tasks} — the
 * reply reaching the engine — and reassigned by an escalation.
 *
 * <p>Who the caller is, and their groups, come from a forwarded token's roles
 * ({@code realm_access.roles}), as behind a gateway that verified it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "workflow.security.trust-forwarded-token=true")
class HumanTaskInboxJourneyTest {

    @LocalServerPort
    int port;

    @Autowired
    FormRepository forms;

    @Autowired
    FormExecutionRepository executions;

    @Autowired
    CreateTaskUseCase createTask;

    @Autowired
    EscalateTaskUseCase escalateTask;

    @MockitoBean
    StreamBridge streamBridge;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    String formId;
    String stepExecutionId;
    String businessKey;

    @BeforeEach
    void theEngineAsksForAnApprovalByTheFinanceGroup() {
        when(streamBridge.send(anyString(), any())).thenReturn(true);
        formId = "approve-" + UUID.randomUUID();
        forms.save(new Form(formId, "Approve the expense", "", List.of(
                new Field("decision", "Decision", FieldDataType.string, FieldStereotype.regular, true, null))));
        stepExecutionId = UUID.randomUUID().toString();
        businessKey = "expense-" + UUID.randomUUID();
        var assignment = new HumanTaskAssignment(null, List.of(), List.of("finance"), 90,
                "2000-01-01T00:00:00Z", null, businessKey);
        createTask.handle(new CreateTaskCommand(stepExecutionId, "process-1", "expenses", "approve", formId, List.of(
                new Variable("formId", formId),
                new Variable("amount", "1500"),
                new Variable(HumanTaskAssignment.VARIABLE, assignment.toJson()))));
    }

    @Test
    void aMemberOfTheGroupFindsClaimsAndCompletesItAndNobodyElseSeesIt() throws Exception {
        assertThat(inbox("mallory", "sales").get("total").asInt()).isZero();

        var carols = inbox("carol", "/finance");
        assertThat(carols.get("total").asInt()).isEqualTo(1);
        var task = carols.get("items").get(0);
        assertThat(task.get("candidateGroups").get(0).asText()).isEqualTo("finance");
        assertThat(task.get("priority").asInt()).isEqualTo(90);
        assertThat(task.get("overdue").asBoolean()).isTrue();
        var taskId = task.get("id").asText();

        assertThat(call("POST", "/forms/api/tasks/" + taskId + "/claim", "mallory", null, "sales").statusCode())
                .isEqualTo(404);
        var claimed = call("POST", "/forms/api/tasks/" + taskId + "/claim", "carol", null, "finance");
        assertThat(claimed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(claimed.body()).get("assignee").asText()).isEqualTo("carol");
        // Claimed by carol: another member of the group can no longer take it.
        assertThat(call("POST", "/forms/api/tasks/" + taskId + "/claim", "erin", null, "finance").statusCode())
                .isEqualTo(404);
        assertThat(inbox("carol", "finance", "scope=mine").get("total").asInt()).isEqualTo(1);

        var completed = call("POST", "/forms/api/tasks/" + taskId + "/complete", "carol",
                "{\"values\": {\"decision\": \"APPROVE\"}}", "finance");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);

        assertThat(executions.findById(taskId).orElseThrow().status()).isEqualTo(FormExecutionStatus.COMPLETED);
        var replies = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge, atLeastOnce()).send(eq("upstream"), replies.capture());
        assertThat(replies.getAllValues()).anySatisfy(reply -> {
            assertThat(reply).isInstanceOf(TaskStatusChanged.class);
            var status = (TaskStatusChanged) reply;
            assertThat(status.taskExecutionId()).isEqualTo(stepExecutionId);
            assertThat(status.status()).isEqualTo(TaskStatus.COMPLETED);
        });
    }

    @Test
    void anEscalationHandsTheTaskToItsNewCandidates() throws Exception {
        escalateTask.handle(new HumanTaskEscalationRequested(stepExecutionId, "process-1", "approve", 1,
                HumanTaskEscalationRequested.REASSIGN,
                new HumanTaskAssignment(null, List.of(), List.of("managers"), null, null, null, null), null, "now"));

        assertThat(inbox("carol", "finance").get("total").asInt()).isZero();
        var managers = inbox("mia", "managers", "scope=groups");
        assertThat(managers.get("total").asInt()).isEqualTo(1);
        assertThat(managers.get("items").get(0).get("escalationLevel").asInt()).isEqualTo(1);
    }

    @Test
    void anonymousIsRefused() throws Exception {
        assertThat(call("GET", "/forms/api/tasks", null, null).statusCode()).isEqualTo(401);
    }

    JsonNode inbox(String user, String group, String... query) throws Exception {
        var params = new StringBuilder("businessKey=" + businessKey);
        for (var q : query) {
            params.append('&').append(q);
        }
        var response = call("GET", "/forms/api/tasks?" + params, user, null, group);
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

    /** A token as the gateway forwards it, already verified there, with the caller's roles (their groups). */
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
}
