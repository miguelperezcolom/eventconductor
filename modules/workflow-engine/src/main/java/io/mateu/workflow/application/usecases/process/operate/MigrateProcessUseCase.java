package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.analysis.MigrationRules;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.services.StepExecutions;
import io.mateu.workflow.dtos.events.domain.ProcessMigrated;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.time.EngineClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Moves a live process onto another version of its definition — Camunda's process instance migration,
 * Temporal's upgrade of a running workflow to a new worker version — keeping everything it has done.
 *
 * <p>Planned under the process lock by {@link MigrationPlanner} (the shared {@link MigrationRules}); a
 * plan with an error is not carried out and the refusal is audited. Otherwise every step gets the fate
 * the plan gave it (see {@link MigrationRules.Action} and {@code INSTANCE-MIGRATION-PLAN.md}):
 *
 * <ul>
 *   <li><b>REMAP</b> — the execution takes the target step's id, frozen copy and position.</li>
 *   <li><b>KEEP_TASK</b> — the same, its task left at the worker: its id and dispatch number do not change,
 *       so the answer lands; the target's deadline applies.</li>
 *   <li><b>REDISPATCH</b> — the worker is told to stop (a PROCESS step's child is cancelled), the
 *       execution goes back to CREATED with a new dispatch number (a late answer is dropped) and is
 *       dispatched again under the target.</li>
 *   <li><b>REARM</b> — remapped, its deadline and message subscription recomputed; a forEach container's
 *       iterations are moved with it, each one at a worker kept or re-dispatched by the same rule.</li>
 *   <li><b>HISTORY / DROP</b> — superseded ({@code DISCARDED}): kept for the timeline, out of the flow,
 *       never compensated.</li>
 *   <li><b>ADD / ADD_PASSED</b> — a new execution of a target step; one upstream of where the process is
 *       recorded as passed.</li>
 * </ul>
 *
 * <p>Then the process's snapshot is swapped to the target, the variable mapping applied, a
 * {@link ProcessMigrated} and a {@code ProcessStatusChanged} (the process index learns the version) put on
 * the outbox with it, the operation audited, and a RUNNING process stepped over. A PAUSED one stays
 * paused; an ERROR one stays ERROR, ready for a retry under the target.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrateProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessLockService processLockService;
    final DownstreamEventPublisher downstreamEventPublisher;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final MigrationPlanner planner;
    final OperatorAudit audit;
    final ProcessStatusAnnouncer announcer;
    final MessageSubscriptionService messageSubscriptionService;
    final ObjectProvider<CancelProcessUseCase> cancelProcessUseCase;

    /**
     * Sensitive variables follow the target: the process's policy is switched to the target's before any of
     * its rows is written, so the step rows, the process row and the events carry the values encoded as the
     * target declares — encrypted when it adds a sensitive variable, in clear when it removes one.
     */
    private io.mateu.workflow.application.services.SensitiveVariablePolicies policies =
            io.mateu.workflow.application.services.SensitiveVariablePolicies.none();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void policies(io.mateu.workflow.application.services.SensitiveVariablePolicies policies) {
        this.policies = policies == null ? io.mateu.workflow.application.services.SensitiveVariablePolicies.none() : policies;
    }

    public void handle(MigrateProcessCommand command) {
        var childrenOf = new AtomicReference<List<String>>(List.of());
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(),
                () -> childrenOf.set(doHandle(command)));
        // After the lock, as the cancel cascade does: the children take their own locks.
        for (var stepExecutionId : childrenOf.get()) {
            ChildProcesses.of(processRepository, stepExecutionId).stream()
                    .filter(child -> !ChildProcesses.isFinished(child))
                    .forEach(child -> cancelProcessUseCase.getObject().handle(new CancelProcessCommand(child.getId())));
        }
    }

    /** @return the PROCESS step executions whose running children must now be cancelled */
    private List<String> doHandle(MigrateProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElse(null);
        if (process == null) {
            log.warn("Migrate: no process {} — ignoring", command.processId());
            return List.of();
        }
        var executions = stepExecutionRepository.findByProcess(process);
        MigrationPlanner.Planned planned;
        try {
            planned = planner.plan(process, executions, command.targetVersion(), command.stepMapping(),
                    command.variableMapping());
        } catch (RuntimeException unavailable) {
            audit.refused(process.getId(), null, OperatorAudit.MIGRATE, command.requestedBy(), command.reason(),
                    "migrate to " + version(command.targetVersion()) + ": " + unavailable.getMessage());
            return List.of();
        }
        var plan = planned.plan();
        if (!plan.executable()) {
            audit.refused(process.getId(), null, OperatorAudit.MIGRATE, command.requestedBy(), command.reason(),
                    "migrate from v" + process.getWorkflowDefinitionVersion() + " to v" + planned.target().version()
                            + ": " + plan.errors().stream().map(MigrationRules.Problem::message).toList());
            return List.of();
        }

        // The variables first: what is re-armed below reads the process as the migration leaves it.
        var migrated = process
                .withWorkflowDefinitionJson(toJson(planned.target()))
                .withWorkflowDefinitionVersion(planned.target().version());
        if (!command.variableMapping().isEmpty()) {
            migrated.updateVariables(new TreeMap<>(command.variableMapping()).entrySet().stream()
                    .map(e -> new Variable(e.getKey(), e.getValue())).toList());
        }
        var sourceSensitive = policies.of(process);
        var targetSensitive = policies.of(migrated);

        var to = "v" + planned.target().version();
        var current = StepExecutions.current(executions);
        var byStep = new LinkedHashMap<String, StepExecution>();
        current.forEach(execution -> byStep.putIfAbsent(execution.getStepId(), execution));
        var children = new ArrayList<String>();
        var done = new TreeMap<MigrationRules.Action, List<String>>();
        var saved = new ArrayList<StepExecution>();

        for (var fate : plan.steps()) {
            var action = fate.action();
            if (action == null || action == MigrationRules.Action.CARRY) {
                continue;
            }
            done.computeIfAbsent(action, k -> new ArrayList<>()).add(describe(fate));
            if (action == MigrationRules.Action.ADD || action == MigrationRules.Action.ADD_PASSED) {
                var step = planned.targetStep(fate.targetStepId());
                var position = planned.position(step.id());
                saved.add(action == MigrationRules.Action.ADD
                        ? StepExecution.create(step, process.getId(), position)
                        : StepExecution.passedByMigration(step, process.getId(), position,
                        "Step " + step.id() + " added by the migration to " + to + " upstream of where the process"
                                + " is: recorded as passed, not run."));
                continue;
            }
            var execution = byStep.get(fate.sourceStepId());
            if (execution == null) {
                continue;
            }
            switch (action) {
                case HISTORY -> {
                    execution.supersede(StepExecution.LOOP_DISCARDED);
                    execution.logInfo("Step " + execution.getStepId() + " has no counterpart in " + to
                            + ": kept as history by the migration.");
                    saved.add(execution);
                }
                case DROP -> {
                    execution.cancelQuietly();
                    execution.supersede(StepExecution.LOOP_DISCARDED);
                    execution.logInfo("Step " + execution.getStepId() + " has no counterpart in " + to
                            + ": dropped by the migration before it ran.");
                    saved.add(execution);
                }
                case REMAP, KEEP_TASK, REARM -> {
                    var targetJson = toJson(planned.targetStep(fate.targetStepId()));
                    if (execution.isContainer()) {
                        moveIterations(execution, executions, fate.targetStepId(), targetJson, migrated, to,
                                children, saved);
                    }
                    execution.migrateTo(targetJson, planned.position(fate.targetStepId()), migrated,
                            action == MigrationRules.Action.REARM, moved(execution.getStepId(), fate.targetStepId(), to,
                                    action == MigrationRules.Action.KEEP_TASK ? "; the task in flight is kept" : ""));
                    saved.add(execution);
                }
                case REDISPATCH -> {
                    stopTask(execution, children);
                    var source = execution.getStepId();
                    execution.migrateTo(toJson(planned.targetStep(fate.targetStepId())),
                            planned.position(fate.targetStepId()), migrated, false, moved(source, fate.targetStepId(), to, ""));
                    execution.resetForRerun("Step " + source + "'s task changed in " + to
                            + ": cancelled and dispatched again by the migration.");
                    saved.add(execution);
                }
                default -> {
                }
            }
        }
        // Earlier passes of a loop follow their step, so the loop's caps and the saga still count them.
        for (var history : executions) {
            if (!history.isSuperseded() || history.isIteration()) {
                continue;
            }
            var target = plan.mapping().get(history.getStepId());
            if (target != null && !target.equals(history.getStepId())) {
                history.renameStep(target);
                saved.add(history);
            }
        }
        if (!sourceSensitive.equals(targetSensitive)) {
            // The sensitivity changed: every row of the process is re-encoded as the target declares, history
            // included — not only the executions the migration moved.
            executions.stream().filter(execution -> saved.stream().noneMatch(s -> s.id().equals(execution.id())))
                    .forEach(saved::add);
        }
        saved.forEach(stepExecutionRepository::save);

        var after = stepExecutionRepository.findByProcess(process);
        migrated = migrated.withCompletionPercentage(completion(StepExecutions.current(after)));
        migrated.send(new ProcessMigrated(process.getId(), process.getWorkflowDefinitionId(),
                process.getWorkflowDefinitionVersion(), planned.target().version(), plan.mapping(),
                command.requestedBy(), EngineClock.now()));
        announcer.announce(migrated);
        processRepository.save(migrated);
        if (!command.variableMapping().isEmpty()) {
            messageSubscriptionService.rearm(migrated);
        }

        audit.performed(migrated, null, OperatorAudit.MIGRATE, command.requestedBy(), command.reason(),
                summary(process.getWorkflowDefinitionVersion(), planned, done,
                        shownMapping(command.variableMapping(), sourceSensitive, targetSensitive)));
        if (ProcessStatus.RUNNING.equals(migrated.getStatus())) {
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
        return children;
    }

    /** A forEach moving: its live iterations take the target's id and copy; one at a worker keeps or loses its task. */
    private void moveIterations(StepExecution container, List<StepExecution> executions, String targetStepId,
                                String targetJson, Process process, String to, List<String> children,
                                List<StepExecution> saved) {
        var targetStep = MigrationPlanner.generic(targetJson);
        for (var iteration : executions) {
            if (!container.getId().equals(iteration.getParentExecutionId())) {
                continue;
            }
            if (iteration.isSuperseded()) {
                iteration.renameStep(targetStepId);
                saved.add(iteration);
                continue;
            }
            var redispatch = iteration.getStatus() != null && iteration.getStatus().isInFlightAtAWorker()
                    && !MigrationRules.sameContract(MigrationPlanner.generic(iteration.getStepJson()), targetStep);
            if (redispatch) {
                stopTask(iteration, children);
            }
            iteration.migrateTo(targetJson, container.getOrder(), process, false,
                    "Iteration " + iteration.getItemIndex() + " of step " + iteration.getStepId() + " moved to " + to
                            + (redispatch ? "; its task changed: cancelled and dispatched again." : "."));
            if (redispatch) {
                iteration.resetForRerun("Iteration " + iteration.getItemIndex() + " re-dispatched by the migration.");
            }
            saved.add(iteration);
        }
    }

    /** Tells the worker to stop; a PROCESS step's running child is cancelled once the lock is released. */
    private void stopTask(StepExecution execution, List<String> children) {
        downstreamEventPublisher.publish(new TaskCancellationRequested(execution.getId()), execution.topic());
        var step = ProcessOperationRules.stepOf(execution);
        if (step != null && StepType.PROCESS.equals(step.type())) {
            children.add(execution.getId());
        }
    }

    private static String moved(String from, String to, String version, String suffix) {
        return "Step " + from + (Objects.equals(from, to) ? "" : " → " + to) + " moved to " + version
                + " by the migration" + suffix + ".";
    }

    private static String describe(MigrationRules.StepPlan fate) {
        if (fate.sourceStepId() == null) {
            return fate.targetStepId();
        }
        if (fate.targetStepId() == null || fate.targetStepId().equals(fate.sourceStepId())) {
            return fate.sourceStepId();
        }
        return fate.sourceStepId() + "→" + fate.targetStepId();
    }

    /** The mapping as the audit line quotes it: a value sensitive in either version reads as the mask. */
    static Map<String, String> shownMapping(Map<String, String> mapping, io.mateu.workflow.crypto.SensitiveNames source,
                                            io.mateu.workflow.crypto.SensitiveNames target) {
        var shown = new java.util.LinkedHashMap<String, String>();
        mapping.forEach((name, value) -> shown.put(name, source.test(name) || target.test(name)
                ? io.mateu.workflow.application.services.SensitiveVariableMasking.MASK : value));
        return shown;
    }

    static String summary(int fromVersion, MigrationPlanner.Planned planned, Map<MigrationRules.Action, List<String>> done,
                          Map<String, String> variables) {
        var what = new StringBuilder("migrated from v").append(fromVersion).append(" to v")
                .append(planned.target().version());
        var renamed = planned.plan().mapping().entrySet().stream()
                .filter(e -> !e.getKey().equals(e.getValue())).map(e -> e.getKey() + "→" + e.getValue()).toList();
        if (!renamed.isEmpty()) {
            what.append("; mapping ").append(renamed);
        }
        done.forEach((action, steps) -> {
            if (action != MigrationRules.Action.REMAP) {
                what.append("; ").append(action.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                        .append(" ").append(steps);
            }
        });
        if (!variables.isEmpty()) {
            what.append("; set ").append(new TreeMap<>(variables).entrySet().stream()
                    .map(e -> e.getKey() + " = " + OperatorAudit.quote(e.getValue())).toList());
        }
        var warnings = planned.plan().warnings();
        if (!warnings.isEmpty()) {
            what.append("; warnings: ").append(warnings.stream().map(MigrationRules.Problem::message).toList());
        }
        return what.toString();
    }

    private static String version(Integer version) {
        return version == null ? "the current version" : "v" + version;
    }

    /** Floored, as the status recompute floors it. */
    private static int completion(List<StepExecution> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        long completed = steps.stream().filter(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus())).count();
        return (int) ((100L * completed) / steps.size());
    }
}
