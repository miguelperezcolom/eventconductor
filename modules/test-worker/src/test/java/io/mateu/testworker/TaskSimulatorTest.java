package io.mateu.testworker;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.testworker.application.PlayedResult;
import io.mateu.testworker.application.TaskSimulator;
import io.mateu.testworker.domain.LogLine;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.TaskScenario;
import io.mateu.workflow.worker.CancelledTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.mateu.testworker.Tasks.task;
import static org.assertj.core.api.Assertions.assertThat;

/** What the worker actually puts on the wire for a given scenario. */
class TaskSimulatorTest {

    private final TaskSimulator simulator = new TaskSimulator();
    private RecordingBroker broker;
    private CancelledTasks cancelled;

    @BeforeEach
    void setUp() {
        broker = new RecordingBroker();
        cancelled = new CancelledTasks();
    }

    @Test
    void a_completing_task_reports_running_and_then_completed_with_its_variables() {
        var played = play(new TaskScenario(0L, Outcome.COMPLETED, null,
                List.of(), List.of(new Variable("seatId", "12A")), 0, 1, false));

        assertThat(played.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING, TaskStatus.COMPLETED);
        assertThat(broker.statuses().getLast().variables())
                .containsExactly(new Variable("seatId", "12A"));
    }

    @Test
    void a_failing_task_says_why_before_it_says_it_failed() {
        var played = play(new TaskScenario(0L, Outcome.ERROR, "card declined",
                List.of(), List.of(), 0, 1, false));

        assertThat(played.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING, TaskStatus.ERROR);
        // The reason goes out first, as an Error log line: a failure the engine cannot explain is
        // the state WorkerReply.failed(reason) exists to end.
        var reasons = broker.logs().stream().map(TaskLogEmitted::message).toList();
        assertThat(reasons).containsExactly("card declined");
        assertThat(broker.logs().getFirst().messageType()).isEqualTo(MessageType.Error);
    }

    @Test
    void no_reply_reports_running_and_then_nothing() {
        var played = play(scenario(Outcome.NO_REPLY));

        assertThat(played.outcome()).isEqualTo(Outcome.NO_REPLY);
        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING);
    }

    @Test
    void log_lines_are_emitted_while_the_task_runs() {
        play(new TaskScenario(0L, Outcome.COMPLETED, null,
                List.of(new LogLine(MessageType.Info, "checking inventory"),
                        new LogLine(MessageType.Error, "inventory is low", 5L)),
                List.of(), 0, 1, false));

        assertThat(broker.logs().stream().map(TaskLogEmitted::message))
                .containsExactlyInAnyOrder("checking inventory", "inventory is low");
    }

    @Test
    void a_scenario_may_ask_for_the_reply_to_be_sent_twice() {
        var played = play(new TaskScenario(0L, Outcome.COMPLETED, null,
                List.of(), List.of(), 0, 2, false));

        assertThat(broker.statusValues())
                .containsExactly(TaskStatus.RUNNING, TaskStatus.COMPLETED, TaskStatus.COMPLETED);
        assertThat(played.note()).contains("replied 2 times");
    }

    @Test
    void a_cancellation_that_overtook_the_task_stops_it_before_it_starts() {
        cancelled.cancel("exec-1");

        var played = play(scenario(Outcome.COMPLETED));

        assertThat(played.outcome()).isNull();
        assertThat(played.note()).isEqualTo("cancelled before it started");
        assertThat(broker.sent).isEmpty();
    }

    @Test
    void a_cancellation_that_arrives_while_the_task_runs_abandons_it() throws Exception {
        var scenario = new TaskScenario(5_000L, Outcome.COMPLETED, null,
                List.of(), List.of(), 0, 1, false);
        var played = new AtomicReference<PlayedResult>();

        simulator.play(broker, task("charge-card"), scenario, cancelled).subscribe(played::set);
        Thread.sleep(50);
        cancelled.cancel("exec-1");

        awaitPlayed(played);
        assertThat(played.get().outcome()).isNull();
        assertThat(played.get().note()).isEqualTo("cancelled while running");
        // RUNNING went out before the cancellation; the completion never did.
        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING);
    }

    @Test
    void a_scenario_may_ignore_the_cancellation_on_purpose() throws Exception {
        var scenario = new TaskScenario(100L, Outcome.COMPLETED, null,
                List.of(), List.of(), 0, 1, true);
        var played = new AtomicReference<PlayedResult>();

        simulator.play(broker, task("charge-card"), scenario, cancelled).subscribe(played::set);
        Thread.sleep(20);
        cancelled.cancel("exec-1");

        awaitPlayed(played);
        // A worker misbehaving exactly as asked: it replies to a task the engine has given up on.
        assertThat(played.get().outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    }

    @Test
    void a_cancellation_landing_as_the_task_finishes_still_stops_the_reply() {
        var scenario = new TaskScenario(0L, Outcome.COMPLETED, null,
                List.of(), List.of(), 0, 1, false);
        // Remembered but never emitted live, which is the gap claim() covers: the work is over,
        // so there is nothing left for the signal to interrupt.
        var task = task("charge-card");
        simulator.play(broker, task, scenario, cancelled).block();
        broker.sent.clear();

        cancelled.cancel("exec-1");
        var played = simulator.play(broker, task, scenario, cancelled).block();

        assertThat(played.outcome()).isNull();
        assertThat(played.note()).isEqualTo("cancelled before it started");
    }

    private void awaitPlayed(AtomicReference<PlayedResult> played) throws InterruptedException {
        for (var waited = 0; waited < 100 && played.get() == null; waited++) {
            Thread.sleep(20);
        }
        assertThat(played.get()).as("the simulation finished").isNotNull();
    }

    private PlayedResult play(TaskScenario scenario) {
        return simulator.play(broker, task("charge-card"), scenario, cancelled).block();
    }

    private static TaskScenario scenario(Outcome outcome) {
        return new TaskScenario(0L, outcome, null, List.of(), List.of(), 0, 1, false);
    }
}
