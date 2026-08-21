package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.services.StepTimeoutDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateProcessUseCase {

    final ProcessRepository processRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final StepExecutionRepository stepExecutionRepository;
    final WorkflowMetrics workflowMetrics;
    // ObjectProvider, like NotifyParentStepService: the step-update pipeline reaches process
    // creation, so a direct dependency would close an injection cycle.
    final ObjectProvider<UpdateStepExecutionUseCase> updateStepExecutionUseCase;

    /**
     * Fallback timeout, in milliseconds, for ACTION and RULE steps that declare none. Zero — the
     * default — leaves them with no deadline, which means a lost dispatch or a lost worker reply
     * stops that process permanently and invisibly. See {@link StepTimeoutDefaults}.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.default-step-timeout-ms:0}")
    long defaultStepTimeoutMillis;

    /**
     * How deeply PROCESS steps may nest — a parent's child's child, and so on. Reaching it refuses
     * the next child and fails the PROCESS step that asked for it.
     *
     * <p>This is the only thing standing between the engine and a recursive definition.
     * {@code checkInvariants} refuses a workflow that names <em>itself</em> as its child, which
     * catches the obvious spelling and none of the others: A starting B starting A validates
     * cleanly, because neither definition can see the other, and each generation starts the next
     * for as long as the database will take rows. A depth limit needs no cross-definition
     * knowledge, and it also catches the case a static check never could — a chain assembled at
     * runtime through dynamically injected steps.
     *
     * <p>Twenty is far past any modelled hierarchy and far short of trouble.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.max-process-depth:20}")
    int maxProcessDepth;

    public void handle(CreateProcessCommand command) {
        // Idempotency: a redelivered creation event carries the same processId and/or
        // businessKey — skip silently instead of creating a duplicate process.
        if (command.processId() != null && !command.processId().isBlank()) {
            if (processRepository.findById(command.processId()).isPresent()) {
                log.warn("Process with process Id '{}' already exists, ignoring duplicate creation request",
                        command.processId());
                return;
            }
        }
        if (command.businessKey() != null && !command.businessKey().isBlank()) {
            if (processRepository.findByBusinessKey(command.businessKey()).isPresent()) {
                log.warn("Process with business key '{}' already exists, ignoring duplicate creation request",
                        command.businessKey());
                return;
            }
        }

        // A child of a child of a child…: refuse before creating anything, and fail the PROCESS
        // step that asked, so a recursive definition surfaces as one failed step instead of as an
        // unbounded fan-out. See maxProcessDepth.
        if (command.parentStepExecutionId() != null && !command.parentStepExecutionId().isBlank()
                && ancestorCount(command.parentStepExecutionId()) >= maxProcessDepth) {
            log.error("Refusing to create a child process of workflow definition '{}' for business key"
                            + " '{}': it would nest more than {} levels deep, which a recursive"
                            + " definition is the usual cause of",
                    command.workflowDefinitionId(), command.businessKey(), maxProcessDepth);
            updateStepExecutionUseCase.getObject().handle(new UpdateStepExecutionCommand(
                    command.parentStepExecutionId(),
                    List.of(),
                    "Child process not started: workflow '" + command.workflowDefinitionId()
                            + "' would nest more than " + maxProcessDepth + " levels deep."
                            + " Check for a cycle between PROCESS steps.",
                    io.mateu.workflow.domain.aggregates.StepExecutionStatus.ERROR));
            return;
        }

        // The fallback timeout is applied here, at the one moment a definition becomes a
        // process, and nowhere else. Both copies this method freezes — the step's stepJson and
        // the process's definition snapshot — then carry it, while the stored definition the UI
        // reads and writes back stays exactly as its author wrote it. Applying it in the
        // repository instead would let an editor round-trip bake the default in permanently.
        var workflowDefinition = StepTimeoutDefaults.applyTo(
                workflowDefinitionRepository.findById(command.workflowDefinitionId()).orElseThrow(),
                defaultStepTimeoutMillis);

        // A disabled workflow accepts no new instances — which the cron scheduler already
        // honoured and this path did not, so anything creating a process directly (the UI, an
        // upstream event, MCP) walked straight past it. Either source can say no: an operator
        // taking it out of service, or the definition itself declaring that it is not to run.
        if (!workflowDefinition.status().accceptsNewInstances()) {
            log.warn("Workflow definition '{}' is {} — process creation for business key '{}' ignored",
                    workflowDefinition.id(), workflowDefinition.status(), command.businessKey());
            return;
        }
        AtomicInteger position = new AtomicInteger(1);
        var stepExecutions = workflowDefinition.steps().stream()
                .map(step -> StepExecution.create(step, command.processId(), position.getAndIncrement())).toList();

        stepExecutions.forEach(stepExecutionRepository::save);

        var process = Process
                .create(
                        command.processId(),
                        workflowDefinition,
                        command.businessKey(),
                        command.variables() != null?command.variables(): List.of(),
                        command.parentStepExecutionId()
                );
        if (workflowDefinition.paused()) {
            // Born paused: creation is deliberately still accepted while the definition is
            // paused (cron included), but nothing may start — the orchestration gate holds
            // everything until the definition (or the process) is resumed. The @With copies
            // start with an empty event list, so carry the ProcessCreated event over.
            var events = process.popEvents();
            process = process.withStatus(ProcessStatus.PAUSED).withPausedAt(LocalDateTime.now());
            events.forEach(process::send);
            log.info("Workflow definition '{}' is paused — process {} created PAUSED",
                    workflowDefinition.id(), process.getId());
        }
        processRepository.save(process);

        workflowMetrics.processStarted(command.workflowDefinitionId());

        // enviar evento proceso creado (para step over)


    }

    /**
     * How many processes stand between this one and a top-level start, walking parent step
     * execution to owning process and up again. Bounded by the limit itself: the answer is only
     * ever compared against it, so there is no reason to walk a chain further than that — and a
     * parent chain that somehow looped would otherwise walk for ever.
     */
    private int ancestorCount(String parentStepExecutionId) {
        int depth = 0;
        var stepExecutionId = parentStepExecutionId;
        while (stepExecutionId != null && depth <= maxProcessDepth) {
            depth++;
            var parentStep = stepExecutionRepository.findById(stepExecutionId).orElse(null);
            if (parentStep == null) {
                break;
            }
            var parentProcess = processRepository.findById(parentStep.getProcessId()).orElse(null);
            if (parentProcess == null) {
                break;
            }
            stepExecutionId = parentProcess.getParentStepExecutionId();
        }
        return depth;
    }

}
