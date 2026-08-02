package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;

import java.time.LocalDateTime;
import java.util.List;

public interface StepExecutionRepository extends CrudStore<StepExecution> {

    List<StepExecution> findByProcess(Process process);

    List<StepExecution> findPendingOrRunning();

    /**
     * The live (PENDING or RUNNING) step executions of a single process. Callers that already
     * know the process must use this instead of filtering {@link #findPendingOrRunning()} in
     * memory: that scan grows with the number of live steps across the whole system, and the
     * schedulers fan out one check per due process, so an in-memory filter turns a single scan
     * tick into one full table load per process it finds.
     */
    List<StepExecution> findPendingOrRunningByProcessId(String processId);

    /**
     * The live step executions whose materialised deadline has passed — the scheduler's whole
     * working set. Backed by an index on the deadline, so the cost tracks the work that is
     * actually due rather than the number of steps waiting, which is what makes long waits
     * (a TIMER pending for weeks) free between their start and their due moment.
     */
    List<StepExecution> findDue(LocalDateTime now);

    /**
     * Live step executions that started before the deadline was materialised and therefore carry
     * none. Empty on any engine that has only ever run this version; non-empty exactly once,
     * right after the upgrade, for the steps that were already in flight. See
     * {@code StepDeadlineBackfillRunner}.
     */
    List<StepExecution> findLiveWithoutDeadline();

}
