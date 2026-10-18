package io.mateu.workflow.application.usecases.process.parentnotify;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The child processes a PROCESS step execution has started, found by their deterministic business
 * keys.
 *
 * <p>The first child of step execution {@code X} is {@code parent:X}, and that is the only one most
 * steps ever have. A step that runs its child again — an automatic retry after the child failed, an
 * operator retry, a process restart — starts the next one as {@code parent:X#2}, then
 * {@code parent:X#3}, … The keys stay deterministic, so a redelivered creation event still finds the
 * child it already created, and the earlier children keep their keys and their history.
 */
public final class ChildProcesses {

    /** A child in one of these will not move again on its own: the parent is no longer waiting on it. */
    private static final Set<ProcessStatus> FINISHED = EnumSet.of(
            ProcessStatus.COMPLETED, ProcessStatus.ERROR, ProcessStatus.CANCELLED,
            ProcessStatus.COMPENSATED, ProcessStatus.COMPENSATION_FAILED);

    /** The business key of the first child of a PROCESS step execution. */
    public static String firstKey(String parentStepExecutionId) {
        return "parent:" + parentStepExecutionId;
    }

    /** The business key of the {@code n}th child (1-based) of a PROCESS step execution. */
    public static String key(String parentStepExecutionId, int n) {
        return n <= 1 ? firstKey(parentStepExecutionId) : firstKey(parentStepExecutionId) + "#" + n;
    }

    /** Every child started so far, oldest first. One lookup per child, plus one. */
    public static List<Process> of(ProcessRepository processRepository, String parentStepExecutionId) {
        var children = new ArrayList<Process>();
        for (int n = 1; ; n++) {
            var child = processRepository.findByBusinessKey(key(parentStepExecutionId, n));
            if (child.isEmpty()) {
                return children;
            }
            children.add(child.get());
        }
    }

    public static boolean isFinished(Process child) {
        return child.getStatus() != null && FINISHED.contains(child.getStatus());
    }

    private ChildProcesses() {
    }
}
