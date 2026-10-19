package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class InMemoryProcessRepositoryTest {

    @Mock ProcessDomainEventUseCase processDomainEventUseCase;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ObjectProvider<StepExecutionRepository> stepExecutionRepositoryProvider;

    InMemoryProcessRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryProcessRepository();
        ReflectionTestUtils.setField(repo, "processDomainEventUseCase", processDomainEventUseCase);
        // Read model off: announceIfChanged short-circuits, so save behaves as before.
        ReflectionTestUtils.setField(repo, "processStatusAnnouncer",
                new io.mateu.workflow.application.services.ProcessStatusAnnouncer(false, null));
        lenient().when(stepExecutionRepositoryProvider.getIfAvailable()).thenReturn(stepExecutionRepository);
        ReflectionTestUtils.setField(repo, "stepExecutionRepository", stepExecutionRepositoryProvider);
    }

    private Process process(String id, String businessKey) {
        return Process.builder().id(id).businessKey(businessKey)
                .status(ProcessStatus.PENDING).variables(List.of()).build();
    }

    @Test
    void savesAndFindsById() {
        repo.save(process("p-1", "BK-1"));
        assertThat(repo.findById("p-1")).isPresent();
    }

    @Test
    void findsByBusinessKeyThroughSavesAndDeletes() {
        // The lookup reads an index rather than scanning the store; it has to agree with the scan.
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        repo.save(process("p-1", "BK-1")); // a re-save keeps the key

        assertThat(repo.findByBusinessKey("BK-1").map(Process::id)).contains("p-1");
        assertThat(repo.findByBusinessKey("BK-2").map(Process::id)).contains("p-2");
        assertThat(repo.findByBusinessKey("BK-3")).isEmpty();

        repo.deleteAllById(List.of("p-1"));
        assertThat(repo.findByBusinessKey("BK-1")).isEmpty();
        assertThat(repo.findByBusinessKey("BK-2").map(Process::id)).contains("p-2");
    }

    @Test
    void aProcessSavedUnderANewKeyIsFoundByTheNewOneOnly() {
        repo.save(process("p-1", "BK-old"));
        repo.save(process("p-1", "BK-new"));

        assertThat(repo.findByBusinessKey("BK-old")).isEmpty();
        assertThat(repo.findByBusinessKey("BK-new").map(Process::id)).contains("p-1");
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findAllReturnsAll() {
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteAllByIdRemovesEntries() {
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        repo.deleteAllById(List.of("p-1"));
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById("p-1")).isEmpty();
    }

    @Test
    void findByBusinessKeyReturnsMatchingProcess() {
        repo.save(process("p-1", "BK-1"));
        repo.save(process("p-2", "BK-2"));
        assertThat(repo.findByBusinessKey("BK-1")).isPresent()
                .get().extracting(Process::id).isEqualTo("p-1");
    }

    @Test
    void findByBusinessKeyReturnsEmptyWhenNotFound() {
        assertThat(repo.findByBusinessKey("MISSING")).isEmpty();
    }

    // ─── findStalled: the JPA query's semantics (FindStalledProcessesH2Test) over the heap ───

    static final LocalDateTime LONG_AGO = LocalDateTime.now().minusHours(3);
    static final LocalDateTime IDLE_BEFORE = LocalDateTime.now().minusHours(1);

    private Process running(String id) {
        return process(id, "BK-" + id).withStatus(ProcessStatus.RUNNING);
    }

    private StepExecution step(String processId, StepExecutionStatus status, LocalDateTime finishedAt) {
        return StepExecution.builder().id(processId + "-" + status).processId(processId)
                .status(status).finishedAt(finishedAt).build();
    }

    @Test
    void aRunningProcessWithNothingLeftToRunIsStalled() {
        repo.save(running("p-1"));
        lenient().when(stepExecutionRepository.findByProcessId("p-1")).thenReturn(List.of(
                step("p-1", StepExecutionStatus.COMPLETED, LONG_AGO),
                step("p-1", StepExecutionStatus.CREATED, null)));

        assertThat(repo.findStalled(IDLE_BEFORE, 20)).containsExactly("p-1");
    }

    @ParameterizedTest
    @EnumSource(value = StepExecutionStatus.class,
            names = {"PENDING", "RUNNING", "AWAITING_RETRY", "WAITING_ON_LOCK"})
    void aStepThatIsStillGoingKeepsTheProcessOffTheReport(StepExecutionStatus live) {
        repo.save(running("p-1"));
        lenient().when(stepExecutionRepository.findByProcessId("p-1")).thenReturn(List.of(
                step("p-1", StepExecutionStatus.COMPLETED, LONG_AGO),
                step("p-1", live, null)));

        assertThat(repo.findStalled(IDLE_BEFORE, 20)).isEmpty();
    }

    @Test
    void aProcessThatMovedRecentlyNeverMovedOrIsNotRunningIsNotStalled() {
        repo.save(running("recent"));
        repo.save(running("never-moved"));
        repo.save(process("paused", "BK-paused").withStatus(ProcessStatus.PAUSED));
        lenient().when(stepExecutionRepository.findByProcessId("recent")).thenReturn(List.of(
                step("recent", StepExecutionStatus.COMPLETED, LocalDateTime.now())));
        lenient().when(stepExecutionRepository.findByProcessId("never-moved")).thenReturn(List.of(
                step("never-moved", StepExecutionStatus.CREATED, null)));
        lenient().when(stepExecutionRepository.findByProcessId("paused")).thenReturn(List.of(
                step("paused", StepExecutionStatus.COMPLETED, LONG_AGO)));

        assertThat(repo.findStalled(IDLE_BEFORE, 20)).isEmpty();
    }

    @Test
    void theMostRecentlyStalledComeFirstUpToTheLimit() {
        repo.save(running("older"));
        repo.save(running("newer"));
        lenient().when(stepExecutionRepository.findByProcessId("older")).thenReturn(List.of(
                step("older", StepExecutionStatus.COMPLETED, LONG_AGO.minusHours(1))));
        lenient().when(stepExecutionRepository.findByProcessId("newer")).thenReturn(List.of(
                step("newer", StepExecutionStatus.COMPLETED, LONG_AGO)));

        assertThat(repo.findStalled(IDLE_BEFORE, 20)).containsExactly("newer", "older");
        assertThat(repo.findStalled(IDLE_BEFORE, 1)).containsExactly("newer");
    }

    /** The memory store keeps the database's UNIQUE business key: one holder, found by the key; a released run frees it. */
    @org.junit.jupiter.api.Test
    void aBusinessKeyHasOneHolderAsInTheDatabase() {
        var first = io.mateu.workflow.domain.aggregates.Process.builder().id("p-1").businessKey("k-1")
                .status(io.mateu.workflow.domain.aggregates.ProcessStatus.RUNNING)
                .created(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        repo.save(first);
        org.assertj.core.api.Assertions.assertThat(repo.findByBusinessKey("k-1")).get()
                .extracting(io.mateu.workflow.domain.aggregates.Process::getId).isEqualTo("p-1");

        var second = io.mateu.workflow.domain.aggregates.Process.builder().id("p-2").businessKey("k-1")
                .status(io.mateu.workflow.domain.aggregates.ProcessStatus.RUNNING)
                .created(java.time.LocalDateTime.of(2026, 1, 2, 0, 0)).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.save(second))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThat(repo.findById("p-2")).isEmpty();

        repo.releaseBusinessKey("p-1");
        repo.save(second);
        org.assertj.core.api.Assertions.assertThat(repo.findByBusinessKey("k-1")).get()
                .extracting(io.mateu.workflow.domain.aggregates.Process::getId).isEqualTo("p-2");
        org.assertj.core.api.Assertions.assertThat(repo.findAllByBusinessKey("k-1"))
                .extracting(io.mateu.workflow.domain.aggregates.Process::getId).containsExactly("p-1", "p-2");
        // Saving the holder again is no duplicate.
        repo.save(second);
    }
}
