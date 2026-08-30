package io.mateu.testworker;

import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.testworker.application.DefaultScenarioSeeder;
import io.mateu.testworker.application.ScenarioResolver;
import io.mateu.testworker.application.SimulatedTaskHandler;
import io.mateu.testworker.application.TaskSimulator;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.testworker.infra.out.persistence.InMemoryReceivedTaskStore;
import io.mateu.testworker.infra.out.persistence.InMemoryTaskOverrideStore;
import io.mateu.workflow.worker.CancelledTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.mateu.testworker.Tasks.task;
import static io.mateu.testworker.Tasks.testConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** The whole path: resolve, record, play, record again. */
class SimulatedTaskHandlerTest {

    private RecordingBroker broker;
    private InMemoryReceivedTaskStore receivedTasks;
    private SimulatedTaskHandler handler;
    private final CancelledTasks cancelled = new CancelledTasks();

    @BeforeEach
    void setUp() {
        broker = new RecordingBroker();
        receivedTasks = new InMemoryReceivedTaskStore(100);
        handler = new SimulatedTaskHandler(
                new ScenarioResolver(new InMemoryTaskOverrideStore(), Duration.ofMillis(1)),
                new TaskSimulator(),
                receivedTasks,
                new DefaultScenarioSeeder(new InMemoryTaskOverrideStore()));
    }

    @Test
    void every_task_is_written_down_with_the_source_that_answered_it() {
        handler.handle(broker, task("charge-card"), cancelled).block();

        var row = receivedTasks.findAll().getFirst();
        assertThat(row.taskId()).isEqualTo("charge-card");
        assertThat(row.source()).isEqualTo(ScenarioSource.DEFAULT);
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(row.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(row.repliedAt()).isNotNull();
    }

    @Test
    void a_task_that_never_replies_is_still_on_the_record() {
        handler.handle(broker, task("notify", testConfig("""
                { "tasks": { "notify": { "outcome": "NO_REPLY" } } }
                """)), cancelled).block();

        var row = receivedTasks.findAll().getFirst();
        assertThat(row.outcome()).isEqualTo(Outcome.NO_REPLY);
        assertThat(row.note()).isEqualTo("reported RUNNING and went quiet");
        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING);
    }

    @Test
    void failures_before_success_counts_the_redispatches_of_one_task_execution() {
        // The engine retries by re-dispatching the SAME taskExecutionId, counting the attempts
        // itself on the step execution. DIST-13 is where that was learned: counting the step's
        // rows within the process instead meant the retry overwrote the row being counted, the
        // count never left 1, and a scenario asking to fail twice failed until the engine gave up.
        var config = testConfig("""
                { "tasks": { "charge-card": { "durationMs": 0, "failuresBeforeSuccess": 2 } } }
                """);
        var task = task("exec-1", "process-1", "charge-card", config);

        handler.handle(broker, task, cancelled).block();
        handler.handle(broker, task, cancelled).block();
        handler.handle(broker, task, cancelled).block();

        assertThat(broker.statusValues()).containsExactly(
                TaskStatus.RUNNING, TaskStatus.ERROR,
                TaskStatus.RUNNING, TaskStatus.ERROR,
                TaskStatus.RUNNING, TaskStatus.COMPLETED);
        // One task execution, one row, three attempts recorded on it.
        assertThat(receivedTasks.findAll()).hasSize(1);
        assertThat(receivedTasks.findAll().getFirst().attempt()).isEqualTo(3);
    }

    @Test
    void a_second_task_execution_starts_its_own_count() {
        var config = testConfig("""
                { "tasks": { "charge-card": { "durationMs": 0, "failuresBeforeSuccess": 1 } } }
                """);

        handler.handle(broker, task("exec-1", "process-1", "charge-card", config), cancelled).block();
        handler.handle(broker, task("exec-2", "process-2", "charge-card", config), cancelled).block();

        assertThat(broker.statusValues()).containsExactly(
                TaskStatus.RUNNING, TaskStatus.ERROR,
                TaskStatus.RUNNING, TaskStatus.ERROR);
    }

    @Test
    void a_step_is_matched_by_its_step_id_when_the_task_id_is_empty() {
        // Which is every ACTION step: the engine fills taskId only for USER_TASK
        // ("complete-form") and RULE ("evaluate-rule"). A worker keyed on taskId alone would
        // match nothing an ACTION step ever sent it.
        var task = new io.mateu.workflow.dtos.events.integration.TaskExecutionRequested(
                "exec-1", "process-1", "booking", "charge-card", "",
                java.util.List.of(testConfig("""
                        { "tasks": { "charge-card": { "durationMs": 0, "outcome": "ERROR" } } }
                        """)));

        handler.handle(broker, task, cancelled).block();

        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING, TaskStatus.ERROR);
        assertThat(receivedTasks.findAll().getFirst().matchedBy()).isEqualTo("charge-card");
    }

    @Test
    void a_scenario_that_cannot_be_read_fails_the_task_and_says_why_on_the_process() {
        handler.handle(broker, task("charge-card", testConfig("{ not json")), cancelled).block();

        assertThat(broker.statusValues()).containsExactly(TaskStatus.ERROR);
        assertThat(broker.logs().getFirst().message()).contains("TEST_CONFIG could not be read");
        var row = receivedTasks.findAll().getFirst();
        assertThat(row.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(row.note()).contains("TEST_CONFIG could not be read");
    }
}
