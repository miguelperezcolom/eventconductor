package io.mateu.workflowdist.support;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base for the distributed tests (TESTING.md §5). State assertions go straight to
 * PostgreSQL over JDBC, so they keep working while orchestrator pods are killed and
 * restarted; worker-side observations come from {@link WorkerStub}.
 */
public abstract class AbstractDistTest {

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    @BeforeEach
    void resetWorker() {
        WorkerStub.reset();
    }

    /** Publishes a ProcessCreationRequested onto the upstream topic, like any external producer. */
    protected void createProcess(String definitionId, String businessKey, Variable... variables) {
        DistInfra.publishUpstream(new ProcessCreationRequested(definitionId, businessKey, List.of(variables)));
    }

    protected Optional<String> processStatus(String businessKey) {
        return DistInfra.jdbc()
                .queryForList("SELECT status FROM process_entity WHERE business_key = ?", String.class, businessKey)
                .stream().findFirst();
    }

    protected String processId(String businessKey) {
        return DistInfra.jdbc()
                .queryForObject("SELECT id FROM process_entity WHERE business_key = ?", String.class, businessKey);
    }

    protected int completionPercentage(String businessKey) {
        return DistInfra.jdbc().queryForObject(
                "SELECT completion_percentage FROM process_entity WHERE business_key = ?", Integer.class, businessKey);
    }

    protected Map<String, String> stepStatuses(String businessKey) {
        var result = new java.util.HashMap<String, String>();
        DistInfra.jdbc().queryForList(
                        "SELECT s.step_id, s.status FROM step_execution_entity s"
                                + " JOIN process_entity p ON p.id = s.process_id WHERE p.business_key = ?", businessKey)
                .forEach(row -> result.put((String) row.get("step_id"), (String) row.get("status")));
        return result;
    }

    protected int attemptCount(String businessKey, String stepId) {
        return DistInfra.jdbc().queryForObject(
                "SELECT s.attempt_count FROM step_execution_entity s"
                        + " JOIN process_entity p ON p.id = s.process_id WHERE p.business_key = ? AND s.step_id = ?",
                Integer.class, businessKey, stepId);
    }

    protected int pendingOutboxMessages() {
        return DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM outbox_message_entity WHERE status = 'Pending'", Integer.class);
    }

    protected void awaitProcessStatus(String businessKey, String status, Duration timeout) {
        Awaitility.await("process " + businessKey + " reaches " + status)
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> processStatus(businessKey).filter(status::equals).isPresent());
    }

    protected void awaitProcessCompleted(String businessKey) {
        awaitProcessStatus(businessKey, "COMPLETED", DEFAULT_TIMEOUT);
    }
}
