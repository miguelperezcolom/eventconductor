package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.workflow.domain.aggregates.Precondition;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.JoinType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Function;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Which of a process's step executions its Steps tab lists: what the run has reached, plus the path
 * ahead that is certain — never the whole plan.
 *
 * <p>The engine materialises an execution for every step of the definition when the process is created
 * (all CREATED), and the eligibility rules then pick the ones that run. Listing those rows as they
 * are listed every step of every branch, taken or not, as «Created»: a process that went down one side
 * of a CHOICE showed the other side's steps as if they were still to come, and a completed process
 * looked unfinished. So a row is shown when:
 *
 * <ul>
 *   <li>the run reached it — any status but CREATED (a CREATED step queued again for a retry counts:
 *       it has attempts behind it), except a CANCELLED step that never started, which is a step the
 *       cancellation swept up before the run got to it; or</li>
 *   <li>it is certain to run next: while the process is live, a CREATED step all of whose incoming
 *       links (any one of them, for an XOR JOIN or an END — as the orchestrator reads them) come from
 *       a step reached and not failed, or itself certain, through a link with no guard and not out of
 *       a CHOICE. That walks the unconditional sequence ahead up to the next decision — a CHOICE, a
 *       guarded link, a JOIN still waiting on a branch that is not certain — and stops there. Steps
 *       past it join the list when the decision is taken and their predecessor gets going.</li>
 * </ul>
 *
 * <p>The diagram is not filtered by this: it keeps drawing the whole shape, with the overlay on what ran.
 */
final class StepHistory {

    /** How a listed step is labelled: as it is, or as the next thing the run will certainly do. */
    record Entry(StepExecution execution, boolean ahead) {
    }

    private StepHistory() {
    }

    static List<Entry> visible(List<StepExecution> executions, ProcessStatus processStatus) {
        return visible(executions, processStatus, se -> safeStep(se.getStepJson()));
    }

    static List<Entry> visible(List<StepExecution> executions, ProcessStatus processStatus,
                               Function<StepExecution, Step> stepOf) {
        var reached = executions.stream().filter(StepHistory::reached).toList();

        Set<StepExecution> ahead = Collections.newSetFromMap(new IdentityHashMap<>());
        if (isLive(processStatus)) {
            // Step ids whose run is going or done well: a successor through an unguarded link is certain.
            var going = new HashSet<String>();
            reached.stream().filter(StepHistory::goingOrDoneWell).map(StepExecution::getStepId).forEach(going::add);
            var stepById = new HashMap<String, Step>();
            for (var se : executions) {
                var step = stepOf.apply(se);
                if (step != null && se.getStepId() != null) stepById.putIfAbsent(se.getStepId(), step);
            }
            var candidates = executions.stream()
                    .filter(se -> se.getStatus() == StepExecutionStatus.CREATED && !reached(se))
                    .toList();
            // To a fixed point: each pass may make the next step of a sequence certain.
            boolean grew = true;
            while (grew) {
                grew = false;
                for (var se : candidates) {
                    if (ahead.contains(se)) continue;
                    var step = stepOf.apply(se);
                    if (step != null && certain(step, going, stepById)) {
                        ahead.add(se);
                        going.add(se.getStepId());
                        grew = true;
                    }
                }
            }
        }

        var entries = new ArrayList<Entry>();
        reached.stream()
                .sorted(Comparator.comparing(StepHistory::moment, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(StepExecution::getOrder))
                .forEach(se -> entries.add(new Entry(se, false)));
        ahead.stream()
                .sorted(Comparator.comparingLong(StepExecution::getOrder))
                .forEach(se -> entries.add(new Entry(se, true)));
        return entries;
    }

    /** Whether the run got to this step at all. */
    static boolean reached(StepExecution se) {
        var status = se.getStatus();
        if (status == StepExecutionStatus.CREATED) {
            return se.getAttemptCount() > 0;
        }
        if (status == StepExecutionStatus.CANCELLED) {
            return se.getStartedAt() != null || se.getAttemptCount() > 0;
        }
        return true;
    }

    /** Reached and not stopped: what comes after it, unconditionally, will run. */
    private static boolean goingOrDoneWell(StepExecution se) {
        var status = se.getStatus();
        return status != StepExecutionStatus.ERROR
                && status != StepExecutionStatus.TIMEOUT
                && status != StepExecutionStatus.CANCELLED;
    }

    private static boolean certain(Step step, Set<String> going, Map<String, Step> stepById) {
        List<Precondition> links = step.resolvedPreconditions();
        if (links.isEmpty()) {
            // An entry point runs at creation and so is never left behind CREATED; anything else
            // without a way in (a compensation, an on-timeout target) is started by something that
            // may not happen.
            return false;
        }
        boolean anyIsEnough = step.type() == StepType.END
                || (step.type() == StepType.JOIN && step.joinType() == JoinType.XOR);
        var certainLinks = links.stream().filter(link -> certainLink(link, going, stepById));
        return anyIsEnough ? certainLinks.findAny().isPresent() : certainLinks.count() == links.size();
    }

    private static boolean certainLink(Precondition link, Set<String> going, Map<String, Step> stepById) {
        if (link.hasGuard() || !going.contains(link.stepId())) {
            return false;
        }
        var predecessor = stepById.get(link.stepId());
        // Out of a CHOICE is a decision even without a guard (its default branch): it is listed once
        // the CHOICE has picked it and it gets going.
        return predecessor == null || predecessor.type() != StepType.CHOICE;
    }

    private static boolean isLive(ProcessStatus status) {
        return status == null
                || status == ProcessStatus.PENDING
                || status == ProcessStatus.RUNNING
                || status == ProcessStatus.PAUSED
                || status == ProcessStatus.ERROR;
    }

    /** When the step took its turn: its start, or — for the steps nothing dispatches — its finish. */
    private static LocalDateTime moment(StepExecution se) {
        return se.getStartedAt() != null ? se.getStartedAt() : se.getFinishedAt();
    }

    private static Step safeStep(String stepJson) {
        try {
            return stepJson == null ? null : pojoFromJson(stepJson, Step.class);
        } catch (Exception e) {
            return null;
        }
    }
}
