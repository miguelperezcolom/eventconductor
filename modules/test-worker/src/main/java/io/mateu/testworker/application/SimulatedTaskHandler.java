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
import reactor.core.scheduler.Schedulers;

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
 * <p>The store calls are blocking, and they run on {@link Schedulers#boundedElastic()} rather than
 * on the Reactor thread that carried the task in. That is not a detail: the premise for leaving them
 * inline was that the work they interleave with is a {@code delay}, which starves nothing — true of
 * the in-memory map, and false of JPA, where the blocking call is a database write on the same small
 * pool that is supposed to be running every other task. Measured at 5,000 processes against the
 * deployed engine, concurrency collapsed to about 1.5 tasks genuinely in flight with nothing
 * saturated anywhere: worker at 50m CPU, PostgreSQL at 106m and a single active connection.
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
        // Both store calls are handed to boundedElastic, which is what it is for. Reading the
        // delivery count is a query and writing the row is a write; under JPA each is a round trip
        // to PostgreSQL, and left on the Reactor thread they hold the pool that every other task in
        // flight is sharing.
        return blocking(() -> receivedTasks.previousDeliveriesOf(task.taskExecutionId()) + 1)
                .flatMap(attempt -> play(bridge, task, cancelled, attempt));
    }

    /**
     * The rest of the handling, and it runs on the elastic thread the delivery count was read on —
     * {@code flatMap} applies downstream on whichever thread emitted. So the {@code save} inside
     * {@link #record} and the one in {@link #failUnreadable} are off the Reactor pool too, without
     * either of them having to know it. Said out loud because it is load-bearing rather than
     * incidental: moving this call back inline would put two more database writes on the pool.
     */
    private Mono<Void> play(StreamOperations bridge, TaskExecutionRequested task,
                            CancelledTasks cancelled, int attempt) {
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
                .flatMap(played -> blocking(() -> {
                    receivedTasks.save(row.repliedWith(
                            played.outcome(), LocalDateTime.now(),
                            played.note() != null ? played.note() : note));
                    return played;
                }))
                .then();
    }

    /** Runs a blocking store call off the Reactor thread that carried the task in. */
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
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
