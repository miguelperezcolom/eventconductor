package io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters;

import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntity;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepCrudAdapterTest {

    private final StepExecutionEntityRepository repository = mock(StepExecutionEntityRepository.class);

    private StepExecutionEntity entity(String stepId, StepExecutionStatus status,
                                       LocalDateTime startedAt, LocalDateTime finishedAt) {
        var e = mock(StepExecutionEntity.class);
        when(e.getId()).thenReturn(stepId + "-exec");
        when(e.getStepId()).thenReturn(stepId);
        when(e.getStatus()).thenReturn(status.name());
        when(e.getStartedAt()).thenReturn(startedAt);
        when(e.getFinishedAt()).thenReturn(finishedAt);
        return e;
    }

    private List<Step> rowsFor(StepExecutionEntity... entities) {
        when(repository.findAllByProcessIdOrderByOrder("p-1")).thenReturn(List.of(entities));
        return new StepCrudAdapter(repository).withProcessId("p-1").repository().findAll();
    }

    /** The whole point of the two columns: when the step began and when it ended, on the row. */
    @Test
    void carriesStartedAndFinishedOnAFinishedStep() {
        var rows = rowsFor(entity("charge", StepExecutionStatus.COMPLETED,
                LocalDateTime.of(2026, 9, 4, 10, 15, 30),
                LocalDateTime.of(2026, 9, 4, 10, 15, 42)));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.started()).isEqualTo("2026-09-04 10:15:30");
            assertThat(row.finished()).isEqualTo("2026-09-04 10:15:42");
            assertThat(row.status().type()).isEqualTo(StatusType.SUCCESS);
        });
    }

    /**
     * A running step has a start and no end, and that is the row an operator is reading: it says
     * how long the step still going has been going. An empty cell, not a stamped one.
     */
    @Test
    void leavesFinishedEmptyWhileAStepIsStillRunning() {
        var rows = rowsFor(entity("pick", StepExecutionStatus.RUNNING,
                LocalDateTime.of(2026, 9, 4, 10, 16, 0), null));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.started()).isEqualTo("2026-09-04 10:16:00");
            assertThat(row.finished()).isNull();
        });
    }

    /** Created but not picked up yet: neither moment has arrived, so neither is invented. */
    @Test
    void leavesBothEmptyOnAStepNoWorkerHasTakenYet() {
        var rows = rowsFor(entity("ship", StepExecutionStatus.PENDING, null, null));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.started()).isNull();
            assertThat(row.finished()).isNull();
        });
    }
}
