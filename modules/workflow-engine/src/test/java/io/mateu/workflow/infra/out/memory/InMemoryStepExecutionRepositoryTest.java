package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.mateu.core.infra.JsonSerializer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class InMemoryStepExecutionRepositoryTest {

    @Mock ProcessDomainEventUseCase processDomainEventUseCase;

    InMemoryStepExecutionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryStepExecutionRepository();
        ReflectionTestUtils.setField(repo, "processDomainEventUseCase", processDomainEventUseCase);
    }

    private StepExecution se(String id, String processId, StepExecutionStatus status, long order) {
        return StepExecution.builder()
                .id(id).processId(processId)
                .status(status).order(order).build();
    }

    private Process process(String id) {
        return Process.builder().id(id).build();
    }

    @Test
    void savesAndFindsById() {
        repo.save(se("1", "p-1", StepExecutionStatus.CREATED, 0));
        assertThat(repo.findById("1")).isPresent();
    }

    @Test
    void findAllReturnsAll() {
        repo.save(se("1", "p-1", StepExecutionStatus.CREATED, 0));
        repo.save(se("2", "p-2", StepExecutionStatus.PENDING, 0));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(se("1", "p-1", StepExecutionStatus.CREATED, 0));
        repo.deleteAllById(List.of("1"));
        assertThat(repo.findById("1")).isEmpty();
    }

    @Test
    void findByProcessReturnsSortedByOrder() {
        repo.save(se("2", "p-1", StepExecutionStatus.CREATED, 1));
        repo.save(se("1", "p-1", StepExecutionStatus.CREATED, 0));
        repo.save(se("3", "p-2", StepExecutionStatus.CREATED, 0));

        var result = repo.findByProcess(process("p-1"));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("1");
        assertThat(result.get(1).id()).isEqualTo("2");
    }

    @Test
    void findPendingOrRunningFiltersCorrectly() {
        repo.save(se("1", "p-1", StepExecutionStatus.PENDING, 0));
        repo.save(se("2", "p-1", StepExecutionStatus.RUNNING, 0));
        repo.save(se("3", "p-1", StepExecutionStatus.COMPLETED, 0));
        repo.save(se("4", "p-1", StepExecutionStatus.CREATED, 0));

        var result = repo.findPendingOrRunning();
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(se ->
                se.getStatus() == StepExecutionStatus.PENDING ||
                se.getStatus() == StepExecutionStatus.RUNNING);
    }

    @Test
    void findPendingOrRunningByProcessIdFiltersByProcessAndStatus() {
        repo.save(se("1", "p-1", StepExecutionStatus.PENDING, 0));
        repo.save(se("2", "p-1", StepExecutionStatus.RUNNING, 0));
        repo.save(se("3", "p-1", StepExecutionStatus.COMPLETED, 0));
        repo.save(se("4", "p-2", StepExecutionStatus.PENDING, 0));

        assertThat(repo.findPendingOrRunningByProcessId("p-1"))
                .extracting(StepExecution::id)
                .containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void findPendingOrRunningByProcessIdReturnsEmptyForUnknownProcess() {
        repo.save(se("1", "p-1", StepExecutionStatus.PENDING, 0));

        assertThat(repo.findPendingOrRunningByProcessId("p-unknown")).isEmpty();
    }

    private StepExecution started(String id, StepExecutionStatus status, LocalDateTime startedAt, long timeoutMillis) {
        var step = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, "t", null, null, null, null, 0, null, null, null, null, timeoutMillis, 0, false, null, 0, null);
        var stepExecution = StepExecution.builder()
                .id(id).processId("p-1")
                .stepJson(JsonSerializer.toJson(step))
                .status(status)
                .build();
        // withStartedAt arms the deadline, which is exactly what findDue filters on.
        return stepExecution.withStartedAt(startedAt);
    }

    @Test
    void findDueReturnsOnlyLiveStepsWhoseDeadlineHasPassed() {
        var now = LocalDateTime.now();
        repo.save(started("due", StepExecutionStatus.PENDING, now.minusMinutes(10), 60_000));
        repo.save(started("notDue", StepExecutionStatus.PENDING, now, 3_600_000));
        repo.save(started("noDeadline", StepExecutionStatus.PENDING, now.minusMinutes(10), 0));
        repo.save(started("terminal", StepExecutionStatus.COMPLETED, now.minusMinutes(10), 60_000));

        assertThat(repo.findDue(now))
                .extracting(StepExecution::id)
                .containsExactly("due");
    }

    @Test
    void findDueIncludesADeadlineFallingExactlyNow() {
        var now = LocalDateTime.now();
        repo.save(started("exact", StepExecutionStatus.PENDING, now.minusSeconds(60), 60_000));

        assertThat(repo.findDue(now)).hasSize(1);
    }

    private StepExecution waitingForMessage(String id, String messageName, String businessKey) {
        var step = new Step("s1", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null, null, null, null, false, null, null, null, null, null, 0, null, messageName, "businessKey", null, 0, 0, false, null, 0, null);
        var process = Process.builder().id("p-" + id).businessKey(businessKey).variables(List.of()).build();
        return StepExecution.create(step, process.id(), 0).start(process).withId(id);
    }

    @Test
    void findWaitingForMessageMatchesOnNameAndKey() {
        repo.save(waitingForMessage("wanted", "paymentConfirmed", "booking-1"));
        repo.save(waitingForMessage("otherKey", "paymentConfirmed", "booking-2"));
        repo.save(waitingForMessage("otherMessage", "checkInOpened", "booking-1"));

        assertThat(repo.findWaitingForMessage("paymentConfirmed", "booking-1"))
                .extracting(StepExecution::id)
                .containsExactly("wanted");
    }

    @Test
    void findWaitingForMessageIgnoresStepsThatAreNoLongerWaiting() {
        var completed = waitingForMessage("done", "paymentConfirmed", "booking-1");
        completed.updateStatus(StepExecutionStatus.COMPLETED);
        repo.save(completed);

        assertThat(repo.findWaitingForMessage("paymentConfirmed", "booking-1")).isEmpty();
    }

    @Test
    void findWaitingForMessageNeverMatchesAStepWithNoKey() {
        // A correlation expression that cannot be evaluated leaves a null key. Fail-closed
        // means it matches nothing — including a message that carries no key either.
        var step = new Step("s1", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null, null, null, null, false, null, null, null, null, null, 0, null, "paymentConfirmed", "no.such.thing", null, 0, 0, false, null, 0, null);
        var process = Process.builder().id("p-1").variables(List.of()).build();
        var unarmed = StepExecution.create(step, "p-1", 0).start(process);
        assertThat(unarmed.getAwaitingCorrelationKey()).isNull();
        repo.save(unarmed);

        assertThat(repo.findWaitingForMessage("paymentConfirmed", null)).isEmpty();
        assertThat(repo.findWaitingForMessage("paymentConfirmed", "anything")).isEmpty();
    }
}
