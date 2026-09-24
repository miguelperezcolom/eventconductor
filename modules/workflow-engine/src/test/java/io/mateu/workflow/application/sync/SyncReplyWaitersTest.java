package io.mateu.workflow.application.sync;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepositoryForTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SyncReplyWaitersTest {

    InMemoryProcessRepositoryForTests processes = new InMemoryProcessRepositoryForTests();
    SyncReplyWaiters waiters;

    @BeforeEach
    void start() {
        waiters = new SyncReplyWaiters(processes);
        waiters.pollIntervalMs = 20;
        waiters.start();
    }

    @AfterEach
    void stop() {
        waiters.stop();
    }

    private Process process(String id) {
        var process = Process.builder().id(id).variables(List.of()).build();
        processes.put(process);
        return process;
    }

    @Test
    void aReplyAlreadyThereAnswersAtOnce() throws Exception {
        process("p1").recordReply(ProcessReply.replied("r", "{}"));
        var result = waiters.await("p1", Duration.ofSeconds(5)).get(1, TimeUnit.SECONDS);
        assertThat(result).isPresent();
        assertThat(waiters.waitingCount()).isZero();
    }

    @Test
    void aSignalledReplyWakesTheWaiter() throws Exception {
        waiters.pollIntervalMs = 0;
        var process = process("p2");
        var future = waiters.await("p2", Duration.ofSeconds(5));
        assertThat(future).isNotDone();
        assertThat(waiters.isWaitingFor("p2")).isTrue();
        process.recordReply(ProcessReply.replied("r", "{}"));
        waiters.replyRecorded("p2");
        assertThat(future.get(1, TimeUnit.SECONDS)).isPresent();
    }

    @Test
    void thePollFindsAReplyNobodySignalled() throws Exception {
        var process = process("p3");
        var future = waiters.await("p3", Duration.ofSeconds(5));
        process.recordReply(ProcessReply.replied("r", "{}"));
        assertThat(future.get(2, TimeUnit.SECONDS)).isPresent();
    }

    @Test
    void theDeadlineAnswersEmpty() throws Exception {
        process("p4");
        var result = waiters.await("p4", Duration.ofMillis(50)).get(2, TimeUnit.SECONDS);
        assertThat(result).isEmpty();
        assertThat(waiters.waitingCount()).isZero();
    }

    @Test
    void severalCallersOnOneProcessAreAllAnswered() throws Exception {
        var process = process("p5");
        var a = waiters.await("p5", Duration.ofSeconds(5));
        var b = waiters.await("p5", Duration.ofSeconds(5));
        assertThat(waiters.waitingCount()).isEqualTo(2);
        process.recordReply(ProcessReply.replied("r", "{}"));
        waiters.wake("p5");
        assertThat(a.get(1, TimeUnit.SECONDS)).isPresent();
        assertThat(b.get(1, TimeUnit.SECONDS)).isPresent();
    }

    @Test
    void aSignalForAProcessNobodyWaitsForIsIgnored() {
        waiters.replyRecorded("nobody");
        waiters.wake("nobody");
        assertThat(waiters.waitingCount()).isZero();
    }
}
