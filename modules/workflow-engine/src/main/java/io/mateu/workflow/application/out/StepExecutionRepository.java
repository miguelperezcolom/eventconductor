package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;

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

}
