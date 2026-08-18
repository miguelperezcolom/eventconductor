package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-13 — a real orchestrator and the real test worker, driven entirely by scenarios.
 *
 * <p>Every other test in this suite programs its worker from Java: {@code WorkerStub.on(...)} in
 * the same JVM as the assertions. That proves the engine and proves nothing about the worker
 * anybody would actually test with. Here the worker is {@code modules/test-worker} — the code
 * {@code apps/worker-standalone-app} ships — and the only thing this test tells it is a
 * {@code TEST_CONFIG} variable on the process it creates. The instruction travels through Kafka,
 * inside the process, and comes back as behaviour. Nothing in the worker's context can see this
 * class.
 *
 * <p>So each test here is two claims at once: that the engine does the right thing with a slow, a
 * failing, a flaky or a silent task — and that a scenario is enough to ask for one. The second
 * claim is the one no other test makes, and it is the one that decides whether the test worker is
 * worth having.
 *
 * <p>Assertions read PostgreSQL directly: the engine's own tables for what the process did, and the
 * worker's {@code received_task} for what it was asked and what it played. Both are in the same
 * schema, so "the engine spent two retries" and "the worker was handed the task three times" are
 * one query apart — and checking both is the only way a retry test says anything, since either
 * number alone describes one half of a conversation.
 *
 * <p>It has already earned that. On its first run it found two things the worker had assumed and
 * the protocol does not do: that a retry arrives as a new task execution (it does not — the engine
 * re-dispatches the same one, so the worker's attempt counter overwrote itself and a flaky step
 * failed until the retries ran out), and that scenarios are keyed by task id (an {@code ACTION}
 * step carries none; the key that matches is the step id).
 */
class Dist13TestWorkerScenariosTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureTestWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
    }

    @Test
    void a_scenario_drives_a_saga_to_completion_and_hands_back_the_variables_it_promised() {
        createProcess("dist-sim-saga", "sim-happy", testConfig("""
                {
                  "default": { "durationMs": 0 },
                  "tasks": {
                    "reserve-seat": {
                      "durationMs": 50,
                      "logs": [{ "type": "Info", "message": "checking inventory" }],
                      "variables": [{ "name": "seatId", "value": "12A" }]
                    },
                    "charge-card": { "variables": [{ "name": "authCode", "value": "XY-9" }] },
                    "notify":      { "durationMs": 10 }
                  }
                }
                """));

        awaitProcessCompleted("sim-happy");

        assertThat(completionPercentage("sim-happy")).isEqualTo(100);
        assertThat(stepStatuses("sim-happy"))
                .containsEntry("reserve-seat", "COMPLETED")
                .containsEntry("charge-card", "COMPLETED")
                .containsEntry("notify", "COMPLETED")
                .containsEntry("end", "COMPLETED");

        // The variables the scenario promised are process variables now — merged by the engine from
        // the worker's replies, exactly as a real worker's outputs would be.
        assertThat(processVariables("sim-happy"))
                .contains("\"seatId\"").contains("12A")
                .contains("\"authCode\"").contains("XY-9");

        // And the log line it asked for is on the process, not only in the worker's stdout.
        assertThat(logMessages("sim-happy")).contains("checking inventory");

        // Worker-side: three tasks, every one of them answered from the process's own scenario.
        var received = receivedTasks(processId("sim-happy"));
        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(row -> assertThat(row.get("source")).isEqualTo("TEST_CONFIG"));
        // Matched by STEP id, not task id: the engine sends an empty taskId for every ACTION step
        // (only USER_TASK and RULE carry one, "complete-form" and "evaluate-rule"). A scenario
        // keyed on task ids alone would match nothing a worker is ever sent.
        assertThat(received).extracting(row -> row.get("step_id"))
                .containsExactlyInAnyOrder("reserve-seat", "charge-card", "notify");
        assertThat(received).extracting(row -> row.get("matched_by"))
                .containsExactlyInAnyOrder("reserve-seat", "charge-card", "notify");

        // Nothing was compensated: the compensations exist in the definition and were never run.
        assertThat(stepStatuses("sim-happy"))
                .doesNotContainEntry("release-seat", "COMPLETED")
                .doesNotContainEntry("refund-card", "COMPLETED");
    }

    @Test
    void a_task_the_scenario_fails_rolls_the_saga_back() {
        createProcess("dist-sim-saga", "sim-rollback", testConfig("""
                {
                  "default": { "durationMs": 0 },
                  "tasks": {
                    "charge-card": { "outcome": "ERROR", "reason": "card declined" }
                  }
                }
                """));

        awaitProcessStatus("sim-rollback", "COMPENSATED", DEFAULT_TIMEOUT);

        var steps = stepStatuses("sim-rollback");
        assertThat(steps).containsEntry("reserve-seat", "COMPLETED");
        assertThat(steps).containsEntry("charge-card", "ERROR");
        // The step that succeeded was undone…
        assertThat(steps).containsEntry("release-seat", "COMPLETED");
        // …and the one that failed was not: it committed nothing to undo.
        assertThat(steps).doesNotContainEntry("refund-card", "COMPLETED");
        // notify is downstream of the failure and never ran.
        assertThat(steps).doesNotContainEntry("notify", "COMPLETED");

        // The reason reached the process log. Without it the log would say "status changed to
        // ERROR" and the reason would exist only in the worker's stdout — which is the state
        // WorkerReply.failed(reason) exists to end, and the scenario language is how it is asked
        // for.
        assertThat(logMessages("sim-rollback")).contains("card declined");
    }

    @Test
    void a_flaky_task_is_retried_by_the_engine_until_the_scenario_lets_it_through() {
        createProcess("dist-sim-retry", "sim-flaky", testConfig("""
                {
                  "tasks": {
                    "charge-card": { "durationMs": 0, "failuresBeforeSuccess": 2 }
                  }
                }
                """));

        awaitProcessCompleted("sim-flaky");

        // Two failures, then through. The engine's attempt_count is the number of retries it spent,
        // not the number of times the step ran — it starts at 0 and is incremented when a retry is
        // scheduled — so two failures and a success reads as 2 here and as 3 on the worker's row.
        assertThat(attemptCount("sim-flaky", "charge-card")).isEqualTo(2);

        // The worker counted the same three — on ONE row, because a retry re-dispatches the same
        // task execution rather than issuing a new one. This test is where that was learned: the
        // worker first counted the step's rows within the process, the retry overwrote the row it
        // was counting, and the count never left 1, so the step failed until the engine gave up.
        var received = receivedTasks(processId("sim-flaky"));
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().get("attempt")).isEqualTo(3);
        assertThat(received.getFirst().get("outcome")).isEqualTo("COMPLETED");
    }

    @Test
    void a_task_that_never_replies_is_timed_out_by_the_engine() {
        createProcess("dist-sim-timeout", "sim-silent", testConfig("""
                {
                  "tasks": { "notify": { "outcome": "NO_REPLY" } }
                }
                """));

        await("the silent step times out").atMost(DEFAULT_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    assertThat(stepStatuses("sim-silent")).containsEntry("notify", "TIMEOUT");
                    assertThat(processStatus("sim-silent")).contains("ERROR");
                });

        // The worker did take the task and did report RUNNING — it is a worker that hung, not one
        // that never received anything. That distinction is the whole reason NO_REPLY exists as a
        // separate outcome, and it is visible here: the row is on the record.
        var received = receivedTasks(processId("sim-silent"));
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().get("outcome")).isEqualTo("NO_REPLY");
        assertThat(received.getFirst().get("note")).isEqualTo("reported RUNNING and went quiet");
    }

    @Test
    void two_processes_running_at_once_each_get_their_own_scenario() {
        // The property that makes TEST_CONFIG worth preferring over a stored override: the
        // instruction belongs to the process, so two runs of the same definition can disagree about
        // what the same task does, at the same time, on the same worker.
        createProcess("dist-sim-retry", "sim-pair-ok", testConfig("""
                { "tasks": { "charge-card": { "durationMs": 0 } } }
                """));
        createProcess("dist-sim-retry", "sim-pair-flaky", testConfig("""
                { "tasks": { "charge-card": { "durationMs": 0, "failuresBeforeSuccess": 1 } } }
                """));

        awaitProcessCompleted("sim-pair-ok");
        awaitProcessCompleted("sim-pair-flaky");

        // Retries spent: none for the one that was told to succeed, one for the one that was told
        // to fail first. Same definition, same worker, same moment.
        assertThat(attemptCount("sim-pair-ok", "charge-card")).isZero();
        assertThat(attemptCount("sim-pair-flaky", "charge-card")).isEqualTo(1);
    }

    // ── Reading what happened ────────────────────────────────────────────────────────────────

    /** The scenario, as the process carries it. */
    private static Variable testConfig(String json) {
        return new Variable("TEST_CONFIG", json);
    }

    private String processVariables(String businessKey) {
        return DistInfra.jdbc().queryForObject(
                "SELECT variables FROM process_entity WHERE business_key = ?", String.class, businessKey);
    }

    private List<String> logMessages(String businessKey) {
        return DistInfra.jdbc().queryForList(
                "SELECT l.message FROM log_message_entity l"
                        + " JOIN process_entity p ON p.id = l.process_id WHERE p.business_key = ?",
                String.class, businessKey);
    }

    /** What the worker wrote down about the tasks it was given for this process. */
    private List<Map<String, Object>> receivedTasks(String processId) {
        return DistInfra.jdbc().queryForList(
                "SELECT task_id, step_id, attempt, source, matched_by, outcome, note"
                        + " FROM received_task WHERE process_id = ? ORDER BY attempt", processId);
    }
}
