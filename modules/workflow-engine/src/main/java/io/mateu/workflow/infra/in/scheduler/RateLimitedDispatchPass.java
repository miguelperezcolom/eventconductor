package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.ratelimit.DispatchRateLimiter;
import io.mateu.workflow.application.usecases.stepexecution.start.StartStepExecutionUseCase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches the tasks a topic's rate limit held back, as its permits free up — every
 * {@code workflow.workers.rate-limit-interval} (200 ms). Any pod may run it: a waiting task is claimed by one.
 * Each task is dispatched in a transaction of its own (when there is a transaction manager), as the outbox relay
 * dispatches it.
 */
@Component
@Slf4j
public class RateLimitedDispatchPass implements SchedulerPass {

    private final DispatchRateLimiter limiter;
    private final StartStepExecutionUseCase startStepExecution;
    private final TransactionTemplate transactions;
    private ScheduledExecutorService releaser;

    @Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    @Value("${workflow.workers.rate-limit-interval:PT0.2S}")
    java.time.Duration interval = java.time.Duration.ofMillis(200);

    public RateLimitedDispatchPass(DispatchRateLimiter limiter, StartStepExecutionUseCase startStepExecution,
                                   org.springframework.beans.factory.ObjectProvider<PlatformTransactionManager> transactionManager) {
        this.limiter = limiter;
        this.startStepExecution = startStepExecution;
        var manager = transactionManager.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    @PostConstruct
    void start() {
        if (!background || interval.isZero() || interval.isNegative()) {
            return;
        }
        releaser = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "rate-limited-dispatch");
            thread.setDaemon(true);
            return thread;
        });
        releaser.scheduleWithFixedDelay(this::releaseQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (releaser != null) {
            releaser.shutdownNow();
        }
    }

    @Override
    public int runOnce() {
        return limiter.releaseDue(stepExecutionId -> {
            if (transactions == null) {
                startStepExecution.dispatchHeldBack(stepExecutionId);
            } else {
                transactions.executeWithoutResult(status -> startStepExecution.dispatchHeldBack(stepExecutionId));
            }
        });
    }

    @Override
    public Optional<Instant> nextDueAt() {
        return limiter.nextDueAt().map(at -> at.atZone(io.mateu.workflow.time.EngineClock.zone()).toInstant());
    }

    void releaseQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Could not dispatch rate-limited tasks; will retry", e);
        }
    }
}
