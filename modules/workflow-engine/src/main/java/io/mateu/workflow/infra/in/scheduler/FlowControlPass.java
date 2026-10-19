package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.flowcontrol.FlowControlService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Releases the starts flow control holds back — debounces whose quiet period is over, throttled starts whose
 * window has room — every {@code workflow.flow-control.release-interval} (1 s). Any pod may run it: each key is
 * released under its lock, so two pods never release the same start.
 */
@Component
@Slf4j
public class FlowControlPass implements SchedulerPass {

    private final FlowControlService flowControl;
    private ScheduledExecutorService releaser;

    @Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    @Value("${workflow.flow-control.release-interval:PT1S}")
    java.time.Duration interval = java.time.Duration.ofSeconds(1);

    public FlowControlPass(FlowControlService flowControl) {
        this.flowControl = flowControl;
    }

    @PostConstruct
    void start() {
        if (!background || interval.isZero() || interval.isNegative()) {
            return;
        }
        releaser = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "flow-control-release");
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
        return flowControl.releaseDue();
    }

    @Override
    public Optional<Instant> nextDueAt() {
        return flowControl.nextDueAt().map(at -> at.atZone(io.mateu.workflow.time.EngineClock.zone()).toInstant());
    }

    void releaseQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Could not release waiting starts; will retry", e);
        }
    }
}
