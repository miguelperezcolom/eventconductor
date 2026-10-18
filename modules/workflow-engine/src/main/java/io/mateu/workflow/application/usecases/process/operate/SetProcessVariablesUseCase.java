package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Changes the variables of a live process by hand: writes {@code set}, deletes {@code remove}.
 *
 * <p>The operator's correction of data a run got wrong — a worker wrote a bad value, a customer
 * changed a date — without touching a step. What the variables feed follows them, exactly as when a
 * worker writes them: a waiting message step's correlation key and a timer or deadline computed from
 * a variable are re-armed, and on a RUNNING process a step held by a link guard that now holds is
 * released by the step-over that follows. A PAUSED process keeps holding its successors until it is
 * resumed; an ERROR process stays failed (fix the data, then retry).
 *
 * <p>Idempotent: writing what is already there changes nothing and records nothing, so a redelivered
 * request is harmless. Runs under the process lock, read-check-write like every other writer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SetProcessVariablesUseCase {

    final ProcessRepository processRepository;
    final ProcessLockService processLockService;
    final MessageSubscriptionService messageSubscriptionService;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final ProcessOperationRules rules;
    final OperatorAudit audit;

    public void handle(SetProcessVariablesCommand command) {
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(), () -> doHandle(command));
    }

    private void doHandle(SetProcessVariablesCommand command) {
        var process = processRepository.findById(command.processId()).orElse(null);
        if (process == null) {
            log.warn("Set variables: no process {} — ignoring", command.processId());
            return;
        }
        var why = rules.whyNotSetVariables(process);
        if (why != null) {
            audit.refused(process.getId(), null, OperatorAudit.SET_VARIABLES, command.requestedBy(),
                    command.reason(), "the process is " + process.getStatus() + "; " + why);
            return;
        }

        var current = new LinkedHashMap<String, String>();
        process.getVariables().forEach(variable -> current.put(variable.name(), variable.value()));
        var written = new ArrayList<Variable>();
        var changes = new ArrayList<String>();
        if (command.set() != null) {
            for (var variable : command.set()) {
                if (variable == null || variable.name() == null) {
                    continue;
                }
                var had = current.containsKey(variable.name());
                var before = current.get(variable.name());
                if (had && Objects.equals(before, variable.value())) {
                    continue;
                }
                written.add(variable);
                changes.add(had
                        ? "set " + variable.name() + " = " + OperatorAudit.quote(variable.value())
                                + " (was " + OperatorAudit.quote(before) + ")"
                        : "added " + variable.name() + " = " + OperatorAudit.quote(variable.value()));
            }
        }
        var removed = new ArrayList<String>();
        if (command.remove() != null) {
            for (var name : command.remove()) {
                if (name != null && current.containsKey(name) && written.stream().noneMatch(v -> name.equals(v.name()))) {
                    removed.add(name);
                    changes.add("removed " + name + " (was " + OperatorAudit.quote(current.get(name)) + ")");
                }
            }
        }
        if (changes.isEmpty()) {
            log.info("Set variables on process {}: nothing to change", process.getId());
            return;
        }

        process.updateVariables(written);
        process.removeVariables(removed);
        processRepository.save(process);
        // What derives from the variables follows them, as it does when a worker writes them.
        messageSubscriptionService.rearm(process);
        audit.performed(process, null, OperatorAudit.SET_VARIABLES, command.requestedBy(), command.reason(),
                String.join(", ", changes));

        if (ProcessStatus.RUNNING.equals(process.getStatus())) {
            // A step held by a link guard reading one of these may be free to go now.
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
    }
}
