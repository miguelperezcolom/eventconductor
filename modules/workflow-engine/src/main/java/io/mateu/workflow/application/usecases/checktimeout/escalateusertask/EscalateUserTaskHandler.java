package io.mateu.workflow.application.usecases.checktimeout.escalateusertask;

import io.mateu.workflow.analysis.UserTaskRules;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Escalation;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.ExternalEventRenderer;
import io.mateu.workflow.domain.services.HumanTaskAssignments;
import io.mateu.workflow.domain.services.MultiInstance;
import io.mateu.workflow.domain.services.TemplateContext;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.template.Templates;
import io.mateu.workflow.time.EngineClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Fires the escalations of a USER_TASK that came due — the human-task half of the deadline scan.
 *
 * <p>Reached the way a timeout is: the task's next escalation is part of its materialised deadline,
 * the scan finds the step by it and {@code CheckTimeoutUseCase} sends it here rather than to the
 * timeout handler. Under the process lock, re-reading the step first: whatever another pod already
 * fired is no longer due, so a redelivered check is a no-op — each escalation fires once.
 *
 * <p>For each due escalation, in order: it is recorded on the step (which moves the deadline on to the
 * next one), written on the process timeline, counted ({@code eventconductor.tasks.escalations}),
 * sent to the forms engine ({@code HumanTaskEscalationRequested}), and its {@code publishEvent}, if any,
 * goes into the outbox with it. An {@code escalateTo} also ends the step {@code TIMEOUT} with its route
 * set to the target, so the step-over that follows takes the flow there exactly as it takes a declared
 * {@code onTimeoutStepId} — and the task is cancelled the way a timed-out task always is.
 *
 * <p>An escalation that cannot do what it says — a {@code to} that does not render, an event that
 * cannot be published — is still recorded as fired, with the reason on the timeline: an escalation
 * that failed would otherwise be retried on every scan, forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalateUserTaskHandler {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final ProcessLockService processLockService;
    final WorkflowMetrics workflowMetrics;

    /** Where an escalation's event may be published; optional so hand-built instances (tests) still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    io.mateu.workflow.infra.config.EventDestinationsProperties eventDestinations;

    @org.springframework.beans.factory.annotation.Value("${workflow.mode:embedded}")
    String mode = "embedded";

    public void handle(String stepExecutionId) {
        var processId = stepExecutionRepository.findById(stepExecutionId).orElseThrow().getProcessId();
        if (!processLockService.runExclusively(processId, () -> doHandle(stepExecutionId))) {
            log.debug("Could not acquire lock for process {}, skipping the escalation check", processId);
        }
    }

    private void doHandle(String stepExecutionId) {
        // Re-read inside the lock: another pod may have fired these already, or the task completed.
        var execution = stepExecutionRepository.findById(stepExecutionId).orElseThrow();
        var now = EngineClock.now();
        var due = execution.escalationsDue(now);
        if (due.isEmpty()) {
            return;
        }
        var process = processRepository.findById(execution.getProcessId()).orElse(null);
        if (process == null) {
            return;
        }
        var step = pojoFromJson(execution.getStepJson(), Step.class);
        var total = step.escalations().size();
        // An iteration's escalations see its item, as its assignment did.
        var scope = execution.scope();
        Map<String, Object> typedScope = MultiInstance.typed(scope);
        Process view = process;
        if (!scope.isEmpty() && execution.isIteration()) {
            view = process.withVariables(MultiInstance.overlay(process.getVariables(), scope));
        }
        var routed = false;
        for (var escalation : due) {
            routed = fire(execution, step, view, typedScope, escalation, total);
            if (routed) {
                break;
            }
        }
        if (routed) {
            // Final, like a deadline: a retry would start past the moment it escalated at.
            execution.updateStatus(StepExecutionStatus.TIMEOUT, true);
            workflowMetrics.stepExecutionFinished(execution.getWorkflowDefinitionId(), StepExecutionStatus.TIMEOUT,
                    Duration.between(execution.getStartedAt(), now));
        }
        stepExecutionRepository.save(execution);
    }

    /** Fires one escalation on {@code execution}; true when it routed the process elsewhere. */
    private boolean fire(StepExecution execution, Step step, Process view, Map<String, Object> typedScope,
                         StepExecution.DueEscalation due, int total) {
        var escalation = due.escalation();
        var action = escalation.effectiveAction();
        var level = due.level();
        var prefix = "Escalation " + level + " of " + total + " (after " + escalation.after() + ")";
        HumanTaskAssignment reassignTo = null;
        String target = null;
        String outcome;
        switch (action) {
            case UserTaskRules.REASSIGN -> {
                try {
                    var context = TemplateContext.of(view, step, execution.id(), typedScope);
                    var rendered = HumanTaskAssignments.render(escalation.to(), context, EngineClock.instant(),
                            view.getBusinessKey());
                    var current = execution.assignment();
                    reassignTo = current == null ? rendered : current.reassignedTo(rendered);
                    outcome = "task reassigned to " + describe(reassignTo);
                } catch (IllegalArgumentException | Templates.TemplateException e) {
                    execution.logError(prefix + ": its 'to' could not be rendered, so the task keeps its assignment: "
                            + e.getMessage());
                    outcome = "task not reassigned";
                }
            }
            case UserTaskRules.ESCALATE_TO -> {
                target = escalation.escalateTo();
                outcome = "task closed, the process goes on at step " + target;
            }
            default -> outcome = "task still open, escalation announced";
        }
        execution.escalated(level, action, reassignTo, target);
        execution.logInfo(prefix + ": " + outcome + ".");
        workflowMetrics.taskEscalated(execution.getWorkflowDefinitionId(), action);
        if (escalation.publishEvent() != null) {
            publish(execution, step, view, typedScope, escalation, level, action, prefix);
        }
        return target != null;
    }

    private void publish(StepExecution execution, Step step, Process view, Map<String, Object> typedScope,
                         Escalation escalation, int level, String action, String prefix) {
        try {
            var context = new LinkedHashMap<String, Object>();
            context.put("level", level);
            context.put("action", action);
            context.put("after", escalation.after());
            if (escalation.escalateTo() != null) {
                context.put("escalateTo", escalation.escalateTo());
            }
            var assignment = execution.assignment();
            if (assignment != null) {
                context.put("assignee", assignment.assignee());
                context.put("candidateUsers", assignment.candidateUsers());
                context.put("candidateGroups", assignment.candidateGroups());
            }
            var event = ExternalEventRenderer.render(step.withEvent(escalation.publishEvent()), view,
                    execution.id(), execution.id() + "-escalation-" + level, eventDestinations,
                    "kafka".equals(mode), typedScope, Map.of("escalation", context));
            execution.publish(event);
            execution.logInfo(prefix + ": event '" + event.eventType() + "' published to '" + event.destination() + "'.");
        } catch (IllegalArgumentException | Templates.TemplateException e) {
            log.warn("The event of escalation {} of step execution {} could not be published: {}", level,
                    execution.id(), e.getMessage());
            execution.logError(prefix + ": its event could not be published: " + e.getMessage());
        }
    }

    private static String describe(HumanTaskAssignment assignment) {
        var parts = new java.util.ArrayList<String>();
        if (assignment.assignee() != null) {
            parts.add(assignment.assignee());
        }
        if (!assignment.candidateUsers().isEmpty()) {
            parts.add("candidate users " + assignment.candidateUsers());
        }
        if (!assignment.candidateGroups().isEmpty()) {
            parts.add("candidate groups " + assignment.candidateGroups());
        }
        return parts.isEmpty() ? "nobody in particular" : String.join(", ", parts);
    }
}
