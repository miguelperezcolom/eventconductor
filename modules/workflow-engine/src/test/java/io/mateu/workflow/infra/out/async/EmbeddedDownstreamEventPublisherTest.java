package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.services.EventFailures;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Two properties of embedded dispatch, both learned from a process that stopped and said nothing.
 *
 * <p>A worker that blocks used to hold the thread that dispatched it — in JPA persistence the one
 * relay thread draining the outbox, which is the only thread advancing every process in the JVM.
 * One HTTP call to a service that accepted the connection and never answered stopped the engine:
 * processes created afterwards sat with every step in {@code CREATED}, indistinguishable from a
 * workflow whose preconditions were never met.
 *
 * <p>A worker that threw used to leave its step exactly as it was, {@code PENDING}, waiting for a
 * reply that was never coming. An unhandled throw is not a reported failure — but it is a failure,
 * and a step that will never hear back has to be told so.
 */
class EmbeddedDownstreamEventPublisherTest {

    private static final long TIMEOUT_SECONDS = 5;

    private final UpdateStepExecutionUseCase updateStepExecution = mock(UpdateStepExecutionUseCase.class);
    private final List<EmbeddedDownstreamEventPublisher> started = new ArrayList<>();

    @AfterEach
    void stopPublishers() {
        started.forEach(EmbeddedDownstreamEventPublisher::stop);
    }

    private EmbeddedDownstreamEventPublisher inline(EmbeddedTaskExecutor worker) {
        return publisher(worker, 0, 1000);
    }

    private EmbeddedDownstreamEventPublisher pooled(EmbeddedTaskExecutor worker, int threads, int queue) {
        return publisher(worker, threads, queue);
    }

    private EmbeddedDownstreamEventPublisher publisher(EmbeddedTaskExecutor worker, int threads, int queue) {
        var publisher = new EmbeddedDownstreamEventPublisher(worker, updateStepExecution, threads, queue, 2000);
        publisher.start();
        started.add(publisher);
        return publisher;
    }

    private TaskExecutionRequested aTask() {
        return aTask("step-execution-1");
    }

    private TaskExecutionRequested aTask(String stepExecutionId) {
        return new TaskExecutionRequested(stepExecutionId, "process-1", "definition-1", "createReservation",
                "", List.of());
    }

    /** Captures the command the publisher reports, and releases the test when it arrives. */
    private CountDownLatch captureReport(AtomicReference<UpdateStepExecutionCommand> into) {
        var reported = new CountDownLatch(1);
        doAnswer(invocation -> {
            into.set(invocation.getArgument(0));
            reported.countDown();
            return null;
        }).when(updateStepExecution).handle(any());
        return reported;
    }

    @Test
    void inlineIsTheDefaultAndRunsTheWorkerOnTheCallingThread() {
        var ranOn = new AtomicReference<Thread>();
        var publisher = inline(request -> ranOn.set(Thread.currentThread()));

        publisher.publish(aTask(), null);

        assertThat(ranOn.get()).isSameAs(Thread.currentThread());
    }

    @Test
    void aPoolReturnsTheCallingThreadWhileTheWorkerIsStillBlocked() throws Exception {
        var workerStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publisher = pooled(request -> {
            workerStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 2, 10);

        try {
            publisher.publish(aTask(), null);

            // The point of the whole change: publish() came back although the worker has not.
            assertThat(workerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aBlockedWorkerNoLongerStopsTheNextTaskFromBeingDispatched() throws Exception {
        var release = new CountDownLatch(1);
        var secondRan = new CountDownLatch(1);
        var publisher = pooled(request -> {
            if ("step-execution-1".equals(request.taskExecutionId())) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                secondRan.countDown();
            }
        }, 2, 10);

        try {
            publisher.publish(aTask("step-execution-1"), null);
            publisher.publish(aTask("step-execution-2"), null);

            assertThat(secondRan.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aWorkerThatThrowsFailsItsStepInsteadOfLeavingItPending() {
        var captured = new AtomicReference<UpdateStepExecutionCommand>();
        captureReport(captured);
        var publisher = inline(request -> {
            throw new IllegalStateException("no reservation service");
        });

        publisher.publish(aTask(), null);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().stepId()).isEqualTo("step-execution-1");
        assertThat(captured.get().status()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(captured.get().log()).contains("IllegalStateException", "no reservation service");
    }

    @Test
    void aWorkerThatThrowsOnAPoolThreadFailsItsStepToo() throws Exception {
        var captured = new AtomicReference<UpdateStepExecutionCommand>();
        var reported = captureReport(captured);
        var publisher = pooled(request -> {
            throw new IllegalStateException("no reservation service");
        }, 1, 10);

        publisher.publish(aTask(), null);

        assertThat(reported.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get().status()).isEqualTo(StepExecutionStatus.ERROR);
    }

    @Test
    void aRetryableFailureIsRethrownInlineSoTheOutboxRedeliversIt() {
        var publisher = inline(request -> {
            throw new CannotCreateTransactionException("database is down");
        });

        // The environment, not the step: turning this into a failed step would fail work that
        // has not been attempted, and the message deserves another go instead.
        assertThatThrownBy(() -> publisher.publish(aTask(), null))
                .isInstanceOf(CannotCreateTransactionException.class);
        verifyNoInteractions(updateStepExecution);
    }

    @Test
    void afterAThrowThePoolThreadKeepsTakingWork() throws Exception {
        // Reporting the failure fails too — the database is what broke, after all. The worker
        // thread must survive it, or one bad task takes the pool down with it.
        doThrow(new IllegalStateException("cannot write either")).when(updateStepExecution).handle(any());
        var secondRan = new CountDownLatch(1);
        var publisher = pooled(request -> {
            if ("step-execution-1".equals(request.taskExecutionId())) {
                throw new IllegalStateException("boom");
            }
            secondRan.countDown();
        }, 1, 10);

        publisher.publish(aTask("step-execution-1"), null);
        publisher.publish(aTask("step-execution-2"), null);

        assertThat(secondRan.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aFullPoolRejectsWithAFailureTheOutboxWillOfferAgain() throws Exception {
        var release = new CountDownLatch(1);
        var occupied = new CountDownLatch(1);
        var publisher = pooled(request -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1, 1);

        try {
            publisher.publish(aTask("occupies-the-thread"), null);
            assertThat(occupied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            publisher.publish(aTask("fills-the-queue"), null);

            var rejection = org.assertj.core.api.Assertions
                    .catchThrowable(() -> publisher.publish(aTask("no-room-left"), null));

            assertThat(rejection).isInstanceOf(RejectedExecutionException.class);
            // Backpressure, not a defect: parked as Error the task would never run again.
            assertThat(EventFailures.isRetryable(rejection)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownLetsAnInFlightWorkerFinish() throws Exception {
        var finished = new CountDownLatch(1);
        var publisher = pooled(request -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            finished.countDown();
        }, 1, 10);

        publisher.publish(aTask(), null);
        publisher.stop();

        assertThat(finished.getCount()).isZero();
    }

    @Test
    void anEventThatIsNotATaskIsIgnored() {
        var dispatched = new AtomicReference<TaskExecutionRequested>();
        var publisher = inline(dispatched::set);

        publisher.publish(new TaskCancellationRequested("step-execution-1"), null);

        assertThat(dispatched.get()).isNull();
    }
}
