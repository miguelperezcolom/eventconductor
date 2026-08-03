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
     * The live step executions of ONE process whose materialised deadline has passed. Same index
     * as {@link #findDue(LocalDateTime)} but scoped to a process: the timeout/timer schedulers fan
     * out a per-process check when the global scan finds due work, and this lets that check load
     * only the steps actually due instead of every live step of the process (which then had to
     * deserialise each step's JSON just to recompute a deadline already materialised on the row).
     */
    List<StepExecution> findDueByProcessId(String processId, LocalDateTime now);

    /**
     * The WAIT_FOR_MESSAGE steps subscribed to this message under this correlation key. A null
     * key matches nothing, which is how the fail-closed contract of an unevaluable correlation
     * expression survives being indexed.
     */
    List<StepExecution> findWaitingForMessage(String messageName, String correlationKey);

    /**
     * How many live steps are waiting on a worker, since before {@code startedBefore}, with no
     * deadline to fire — work nothing in the engine will ever look at again.
     *
     * <p>Steps whose waiting is unbounded by design are not counted: a USER_TASK waits for a
     * person, a WAIT_FOR_MESSAGE waits for a message that may never come, a PROCESS waits for its
     * child. Those have no deadline for the same reason the fallback timeout refuses to give them
     * one, and counting them turned this into a number that was never zero anywhere a human ever
     * approved anything.
     *
     * <p>This is the blind spot the deadline index creates. A step with a timeout is safe: the
     * scheduler finds it and the retry path recovers it. A step without one is invisible by
     * construction, so if its dispatch or its reply is lost the process stops, permanently, and
     * no metric, log or query in the engine says anything. On a four-hour run with a broker
     * outage in it, 3 356 processes ended that way and the only thing that noticed was a person
     * wondering why the drain had stopped.
     *
     * <p>Counting them does not fix them — see {@code workflow.default-step-timeout-ms} for
     * that — but a number that can be alerted on is the difference between a stuck process and a
     * silently stuck process.
     *
     * @return zero from implementations that cannot answer cheaply; the caller only reports it
     */
    default long countStalled(LocalDateTime startedBefore) {
        return 0;
    }

}
