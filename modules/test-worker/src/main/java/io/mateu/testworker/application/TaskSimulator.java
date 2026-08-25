package io.mateu.testworker.application;

import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.testworker.domain.LogLine;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.TaskScenario;
import io.mateu.workflow.worker.CancelledTasks;
import io.mateu.workflow.worker.WorkerReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Plays one scenario: waits, says what it was told to say, and finishes the way it was told to
 * finish.
 *
 * <p>Every reply goes through {@link WorkerReply}, exactly as a real worker's would, so a
 * simulated run exercises the same synchronous, retry-or-throw delivery path as the thing it
 * stands in for. A simulator whose replies could vanish where a real worker's could not would
 * quietly prove the wrong thing.
 *
 * <p>Cancellation is honoured at the three points it can arrive — before the task starts, while it
 * is running, and in the gap between the work finishing and the reply going out — unless the
 * scenario says {@code ignoreCancellation}, which exists so that a worker misbehaving in exactly
 * that way can be pointed at a running engine on purpose.
 */
@Service
@Slf4j
public class TaskSimulator {

    public Mono<PlayedResult> play(StreamOperations bridge, TaskExecutionRequested task,
                                   TaskScenario scenario, CancelledTasks cancelled) {
        var id = task.taskExecutionId();
        var honoursCancellation = !scenario.ignoresCancellation();

        if (honoursCancellation && cancelled.claim(id)) {
            log.info("Task {} was cancelled before it reached this worker; not starting it", id);
            return Mono.just(PlayedResult.nothing("cancelled before it started"));
        }

        WorkerReply.running(bridge, task);

        if (scenario.outcome() == Outcome.NO_REPLY) {
            // Reported RUNNING and stops here, on purpose. The engine's step timeout is the thing
            // under test, and nothing else in the scenario language produces this state.
            log.info("Task {} is scenario NO_REPLY: reported RUNNING and going quiet", id);
            return Mono.just(new PlayedResult(Outcome.NO_REPLY, "reported RUNNING and went quiet"));
        }

        var run = Flux.merge(logEmissions(bridge, task, scenario), Mono.delay(scenario.duration()))
                .then(Mono.fromCallable(() -> reply(bridge, task, scenario, cancelled, honoursCancellation)));

        if (!honoursCancellation) {
            return run;
        }
        return run.takeUntilOther(cancelled.when(id))
                // Empty means the signal above cut the work short, which is the whole point of it:
                // the work is abandoned, not merely left unreported.
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("Task {} was cancelled while running; abandoning it", id);
                    return PlayedResult.nothing("cancelled while running");
                }));
    }

    /**
     * The log lines, each at its own offset into the task and all of them concurrently with the
     * work itself — a line at 500 ms goes out at 500 ms whether the task takes 400 ms or four
     * seconds.
     */
    private Flux<Long> logEmissions(StreamOperations bridge, TaskExecutionRequested task,
                                    TaskScenario scenario) {
        return Flux.fromIterable(scenario.logLines())
                .flatMap(line -> Mono.delay(Duration.ofMillis(line.offsetMs()))
                        .doOnNext(tick -> emit(bridge, task, line)));
    }

    private void emit(StreamOperations bridge, TaskExecutionRequested task, LogLine line) {
        WorkerReply.send(bridge, new TaskLogEmitted(
                task.taskExecutionId(), line.type(), line.message()));
    }

    private PlayedResult reply(StreamOperations bridge, TaskExecutionRequested task,
                               TaskScenario scenario, CancelledTasks cancelled,
                               boolean honoursCancellation) {
        var id = task.taskExecutionId();
        if (honoursCancellation && cancelled.claim(id)) {
            log.info("Task {} was cancelled as it finished; not reporting it", id);
            return PlayedResult.nothing("cancelled as it finished");
        }
        var times = scenario.replies();
        for (var attempt = 0; attempt < times; attempt++) {
            if (scenario.outcome() == Outcome.ERROR) {
                WorkerReply.failed(bridge, task, scenario.reportedVariables(), scenario.reason());
            } else {
                WorkerReply.completed(bridge, task, scenario.reportedVariables());
            }
        }
        var note = times > 1 ? "replied " + times + " times, on purpose" : null;
        return new PlayedResult(scenario.outcome(), note);
    }
}
