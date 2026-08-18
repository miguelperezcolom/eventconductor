package io.mateu.testworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.ResolvedScenario;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.testworker.domain.TaskScenario;
import io.mateu.workflow.worker.CancelledTasks;
import io.mateu.workflow.worker.WorkerReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One task, end to end: work out what it should do, write down that it arrived, play it, and write
 * down what happened.
 *
 * <p>The row is written <em>before</em> the scenario is played, not after. A task that is asked to
 * never reply, or that hangs for four minutes, is exactly the task someone will be staring at the
 * UI waiting to see; recording it on the way out would show nothing until it was over, which is
 * the wrong half of the run to be blind for.
 *
 * <p>The store calls are blocking, on a Reactor thread. That is a considered trade for a test
 * tool — the alternative is a reactive data stack for a component whose whole job is to be
 * predictable — and the work it is interleaved with is a {@code delay}, so there is nothing it
 * meaningfully starves.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SimulatedTaskHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ScenarioResolver resolver;
    private final TaskSimulator simulator;
    private final ReceivedTaskStore receivedTasks;

    public Mono<Void> handle(StreamOperations bridge, TaskExecutionRequested task,
                             CancelledTasks cancelled) {
        var attempt = receivedTasks.previousDeliveriesOf(task.taskExecutionId()) + 1;

        ResolvedScenario resolved;
        try {
            resolved = resolver.resolve(task);
        } catch (ScenarioNotReadableException e) {
            return failUnreadable(bridge, task, attempt, e);
        }

        var scenario = forcedFailure(resolved.scenario(), attempt);
        var note = scenario == resolved.scenario()
                ? null
                : "failing attempt %d of %d, as asked".formatted(attempt, scenario.failuresFirst());
        var row = record(task, attempt, resolved, scenario, note);

        return simulator.play(bridge, task, scenario, cancelled)
                .doOnNext(played -> receivedTasks.save(row.repliedWith(
                        played.outcome(), LocalDateTime.now(),
                        played.note() != null ? played.note() : note)))
                .then();
    }

    /**
     * The scenario, forced to fail while the step is still inside its {@code failuresBeforeSuccess}
     * window. Returns the scenario unchanged once it is past it, which is how the caller tells the
     * two cases apart.
     */
    private TaskScenario forcedFailure(TaskScenario scenario, int attempt) {
        var failures = scenario.failuresFirst();
        if (failures <= 0 || attempt > failures) {
            return scenario;
        }
        return scenario.failingWith(
                "simulated failure %d of %d".formatted(attempt, failures));
    }

    /**
     * Fails the task with the parse error as its reason, and records the attempt.
     *
     * <p>The reason reaches the process log through {@code WorkerReply.failed}, which sends it as
     * an {@code Error} line before the failure — so the person who mistyped the JSON reads about
     * it on the process they started, rather than in this worker's stdout.
     */
    private Mono<Void> failUnreadable(StreamOperations bridge, TaskExecutionRequested task,
                                      int attempt, ScenarioNotReadableException e) {
        log.error("Task {} carries a scenario this worker cannot read", task.taskExecutionId(), e);
        var row = new ReceivedTask(task.taskExecutionId(), task.processId(),
                task.workflowDefinitionId(), task.stepId(), task.taskId(), LocalDateTime.now(),
                attempt, ScenarioSource.TEST_CONFIG, null, Outcome.ERROR, 0L,
                LocalDateTime.now(), e.getMessage(), task.variables(), null);
        receivedTasks.save(row);
        WorkerReply.failed(bridge, task, List.of(), e.getMessage());
        return Mono.empty();
    }

    private ReceivedTask record(TaskExecutionRequested task, int attempt, ResolvedScenario resolved,
                                TaskScenario scenario, String note) {
        var row = new ReceivedTask(task.taskExecutionId(), task.processId(),
                task.workflowDefinitionId(), task.stepId(), task.taskId(), LocalDateTime.now(),
                attempt, resolved.source(), resolved.matchedBy(), null, scenario.duration().toMillis(),
                null, note, task.variables(), asJson(scenario));
        receivedTasks.save(row);
        return row;
    }

    /** The scenario as it was resolved, for the record. Unreadable here is not worth failing over. */
    private String asJson(TaskScenario scenario) {
        try {
            return mapper.writeValueAsString(scenario);
        } catch (Exception e) {
            log.warn("The resolved scenario could not be written down", e);
            return null;
        }
    }
}
