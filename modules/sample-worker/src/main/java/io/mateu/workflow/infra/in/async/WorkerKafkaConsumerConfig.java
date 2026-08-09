package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.CancelledTasks;
import io.mateu.workflow.worker.WorkerReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;

/**
 * The worker other workers get copied from, which is why it goes through {@link WorkerReply}
 * rather than calling {@code streamBridge.send} directly.
 *
 * <p>The direct call returns {@code false} when the broker will not take the message, and this
 * class used to discard that. The listener then completes normally, the offset is committed, and
 * a task the worker actually did is never reported — leaving the engine's step in {@code PENDING}
 * with nothing to time it out. Measured across a ninety-second broker outage: 3 352 replies lost
 * and 3 356 processes stuck permanently, with no error logged anywhere.
 *
 * <p>It also used to filter the stream down to {@link TaskExecutionRequested} and drop everything
 * else, which quietly included {@link TaskCancellationRequested}. The engine has always published
 * cancellations — on timeout, and when an operator cancels a process — and this worker, the one
 * everyone copies, ignored them: it kept working and reported a task done for a process that was
 * already over. {@link CancelledTasks} is that half of the protocol, written down beside the
 * reply half for the same reason.
 *
 * <p>The dependency is {@link StreamOperations} rather than {@code StreamBridge} for the reason
 * {@link WorkerReply} gives: the bridge is final, and behaviour that cannot be tested is not
 * behaviour anyone should trust.
 */
@Configuration
@Slf4j
public class WorkerKafkaConsumerConfig {

    final StreamOperations streamBridge;
    final Duration taskDuration;

    public WorkerKafkaConsumerConfig(StreamOperations streamBridge,
                                     @Value("${worker.task-duration:2s}") Duration taskDuration) {
        this.streamBridge = streamBridge;
        this.taskDuration = taskDuration;
    }

    @Bean
    public CancelledTasks cancelledTasks() {
        return new CancelledTasks();
    }

    @Bean
    public Function<Flux<DomainEvent>, Mono<Void>> consumeWorkerEvent(CancelledTasks cancelledTasks) {
        return events -> events.flatMap(event -> route(event, cancelledTasks)).then();
    }

    private Mono<Void> route(DomainEvent event, CancelledTasks cancelledTasks) {
        if (event instanceof TaskCancellationRequested cancellation) {
            cancelledTasks.accept(cancellation);
            return Mono.empty();
        }
        if (event instanceof TaskExecutionRequested task) {
            return execute(task, cancelledTasks);
        }
        return Mono.empty();
    }

    private Mono<Void> execute(TaskExecutionRequested task, CancelledTasks cancelledTasks) {
        // The cancellation may have overtaken the task: they are different messages on different
        // partitions, so arriving second says nothing about which happened first.
        if (cancelledTasks.claim(task.taskExecutionId())) {
            log.info("Task {} was cancelled before it reached this worker; not starting it",
                    task.taskExecutionId());
            return Mono.empty();
        }
        WorkerReply.running(streamBridge, task);
        return Mono.just(task)
                .delayElement(taskDuration)
                // Abandon the work itself, not just its reply: the engine cancelled the step
                // because nothing it produces can be used any more.
                .takeUntilOther(cancelledTasks.when(task.taskExecutionId()))
                // A throw here fails the flux, so the offset is not committed and
                // Kafka redelivers the task. That is the intended outcome, and it is
                // why a worker handler has to be idempotent.
                .doOnNext(done -> {
                    // Cancelled in the window between the work finishing and this reply, where
                    // the signal above has nothing left to interrupt.
                    if (cancelledTasks.claim(done.taskExecutionId())) {
                        log.info("Task {} was cancelled as it finished; not reporting it done",
                                done.taskExecutionId());
                        return;
                    }
                    WorkerReply.completed(streamBridge, done, done.variables());
                })
                .then();
    }

}
