package io.mateu.workflow.application.usecases.process.inject;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Adds steps to a running process at the request of a DYNAMIC step's worker.
 *
 * <p>Add-only: injected steps and their links are materialised alongside the existing ones, never
 * rewriting or removing them. The whole batch is validated first and rejected as a unit — a
 * partial injection would leave the graph in a state no author wrote — and on rejection the
 * DYNAMIC step is failed with the reason, so the normal failure pipeline (and the Errors tab)
 * carries it rather than the process stalling silently.
 *
 * <p>Injected steps get no default wiring: one with no preconditions is simply unreachable, which
 * is a visible programming error in the graph, not something to paper over by auto-attaching it to
 * the entry point.
 *
 * <p>Idempotency is exact: every injected step execution is stamped with the injecting DYNAMIC
 * step's id ({@code injectedByStepExecutionId}), and a re-delivered {@code StepsInjected} for a
 * step that already has such children injects nothing more.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InjectStepsUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final LogMessageRepository logMessageRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;

    /**
     * Runaway guard: the most step executions one process may ever hold, injections included. A
     * DYNAMIC step whose worker injects more steps — each of which could be DYNAMIC and inject
     * again — is an unbounded loop without this. A per-definition override is a later PR; today
     * this one global cap applies to every process.
     */
    @Value("${workflow.dynamic.max-steps-per-process:500}")
    int maxStepsPerProcess;

    public void handle(InjectStepsCommand command) {
        var injectingStep = stepExecutionRepository.findById(command.taskExecutionId()).orElse(null);
        if (injectingStep == null) {
            // A reply for a step execution that does not exist — nothing to inject into.
            log.warn("Ignoring StepsInjected for unknown step execution {}", command.taskExecutionId());
            return;
        }

        var sourceStep = pojoFromJson(injectingStep.getStepJson(), Step.class);
        if (!StepType.DYNAMIC.equals(sourceStep.type())) {
            // Only a DYNAMIC step may inject. A reply from any other type is a misuse of the
            // message, not a runtime failure of the flow — reject it and fail the offending step.
            reject(injectingStep, "step " + injectingStep.getStepId() + " is " + sourceStep.type()
                    + ", only DYNAMIC steps may inject steps");
            return;
        }

        var processId = injectingStep.getProcessId();
        var process = processRepository.findById(processId).orElse(null);
        if (process == null) {
            log.warn("Ignoring StepsInjected for step {}: its process {} no longer exists",
                    command.taskExecutionId(), processId);
            return;
        }

        var existingExecutions = stepExecutionRepository.findByProcess(process);

        // Idempotency: a re-delivered StepsInjected must not inject twice. Exact — every injected
        // step is stamped with the injecting step's id, so a redelivery finds those children and
        // does nothing more.
        if (alreadyInjected(injectingStep, existingExecutions)) {
            log.info("StepsInjected for step {} already applied — skipping duplicate injection",
                    command.taskExecutionId());
            return;
        }

        List<Step> injectedSteps;
        try {
            injectedSteps = listFromJson(command.stepsJson(), Step.class).stream()
                    .map(step -> step.withWorkflowDefinitionId(process.getWorkflowDefinitionId()))
                    .toList();
        } catch (RuntimeException e) {
            reject(injectingStep, "injected steps JSON could not be parsed: " + e.getMessage());
            return;
        }

        var rejection = validate(injectedSteps, existingExecutions);
        if (rejection != null) {
            reject(injectingStep, rejection);
            return;
        }

        materialize(injectedSteps, processId, existingExecutions, injectingStep);

        // Re-evaluate the process so any injected step whose preconditions are already met gets
        // dispatched. This is exactly what a step reaching a terminal status does — the completed
        // DYNAMIC step's StepExecutionStatusChanged drives StepExecutionStatusUpdatedEventHandler,
        // which calls the same StepOverProcessUseCase. Calling it here covers the case the worker
        // injects and reports COMPLETED in the same reply as well as an injection that arrives on
        // its own. StepOverProcessUseCase takes the process lock itself; injection runs under the
        // upstream handler's single-writer routing (per-processId), so this does not re-enter a
        // lock this use case is already holding.
        stepOverProcessUseCase.handle(new StepOverProcessCommand(processId));
    }

    /**
     * Whether this DYNAMIC step already has children from a prior injection. Exact: a child carries
     * the injecting step's id in {@code injectedByStepExecutionId}, stamped at materialisation, so
     * this is a direct match rather than a heuristic over the precondition graph.
     */
    private boolean alreadyInjected(StepExecution injectingStep, List<StepExecution> existingExecutions) {
        return existingExecutions.stream()
                .anyMatch(execution -> injectingStep.id().equals(execution.getInjectedByStepExecutionId()));
    }

    /**
     * Validates the batch as a whole, returning a rejection reason or null when it is safe to
     * inject. Nothing is written before this passes.
     */
    private String validate(List<Step> injectedSteps, List<StepExecution> existingExecutions) {
        var existingIds = new HashSet<String>();
        for (var execution : existingExecutions) {
            existingIds.add(execution.getStepId());
        }

        // Injected ids: unique among themselves AND free of collisions with the process's steps.
        var injectedIds = new HashSet<String>();
        for (var step : injectedSteps) {
            if (step.id() == null || step.id().isBlank()) {
                return "an injected step has no id";
            }
            if (!injectedIds.add(step.id())) {
                return "duplicate injected step id '" + step.id() + "'";
            }
            if (existingIds.contains(step.id())) {
                return "injected step id '" + step.id() + "' collides with an existing step";
            }
        }

        // Every precondition must resolve to a step already in the process or another injected one.
        var knownIds = new HashSet<>(existingIds);
        knownIds.addAll(injectedIds);
        for (var step : injectedSteps) {
            for (var preconditionId : step.preconditionIds()) {
                if (!knownIds.contains(preconditionId)) {
                    return "injected step '" + step.id() + "' references unknown precondition '"
                            + preconditionId + "'";
                }
            }
        }

        // Runaway guard: the process's total step budget, injections included.
        if (existingExecutions.size() + injectedSteps.size() > maxStepsPerProcess) {
            return "step budget exceeded: " + existingExecutions.size() + " existing + "
                    + injectedSteps.size() + " injected > " + maxStepsPerProcess;
        }

        // No cycles over the combined edge set (existing precondition -> step, plus injected).
        if (introducesCycle(injectedSteps, existingExecutions)) {
            return "injected steps would introduce a cycle";
        }

        return null;
    }

    /**
     * Kahn's algorithm over the whole graph — every step in the process plus the injected ones,
     * with an edge from each precondition to the step that waits on it. If any node is left
     * unresolved, the combined graph has a cycle.
     */
    private boolean introducesCycle(List<Step> injectedSteps, List<StepExecution> existingExecutions) {
        var successors = new HashMap<String, List<String>>();
        var inDegree = new HashMap<String, Integer>();

        List<Step> allSteps = new ArrayList<>(injectedSteps);
        existingExecutions.forEach(execution -> allSteps.add(pojoFromJson(execution.getStepJson(), Step.class)));

        allSteps.forEach(step -> {
            inDegree.putIfAbsent(step.id(), 0);
            successors.putIfAbsent(step.id(), new ArrayList<>());
        });
        for (var step : allSteps) {
            for (var preconditionId : step.preconditionIds()) {
                // A precondition pointing outside the known set was already rejected as dangling;
                // guard here anyway so this pure graph check never NPEs on a malformed batch.
                if (!successors.containsKey(preconditionId)) {
                    continue;
                }
                successors.get(preconditionId).add(step.id());
                inDegree.merge(step.id(), 1, Integer::sum);
            }
        }

        var queue = new ArrayDeque<String>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int resolved = 0;
        while (!queue.isEmpty()) {
            var id = queue.poll();
            resolved++;
            for (var successor : successors.get(id)) {
                if (inDegree.merge(successor, -1, Integer::sum) == 0) {
                    queue.add(successor);
                }
            }
        }
        return resolved < inDegree.size();
    }

    /**
     * Creates and saves a step execution for each injected step, with positions continuing after
     * the process's current maximum. Mirrors how {@code CreateProcessUseCase} materialises a
     * definition's steps.
     */
    private void materialize(List<Step> injectedSteps, String processId,
                             List<StepExecution> existingExecutions, StepExecution injectingStep) {
        long maxOrder = existingExecutions.stream().mapToLong(StepExecution::getOrder).max().orElse(0);
        var position = new AtomicInteger((int) maxOrder + 1);
        injectedSteps.stream()
                .map(step -> StepExecution.create(step, processId, position.getAndIncrement(), injectingStep.id()))
                .forEach(stepExecutionRepository::save);
        log.info("Injected {} step(s) into process {} from DYNAMIC step {}",
                injectedSteps.size(), processId, injectingStep.getStepId());
    }

    /**
     * Rejects the whole batch: fails the DYNAMIC step with a clear reason so the normal failure
     * pipeline engages (the process errors, the Errors tab shows why) and logs it. Nothing was
     * written before validation, so there is nothing to undo.
     */
    private void reject(StepExecution injectingStep, String reason) {
        log.warn("Rejecting step injection from step {} of process {}: {}",
                injectingStep.getStepId(), injectingStep.getProcessId(), reason);
        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                injectingStep.getProcessId(),
                injectingStep.id(),
                MessageType.Error.name(),
                "Step injection rejected: " + reason,
                "system"));
        injectingStep.updateStatus(StepExecutionStatus.ERROR);
        stepExecutionRepository.save(injectingStep);
    }
}
