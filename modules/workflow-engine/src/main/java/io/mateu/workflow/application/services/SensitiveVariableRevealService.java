package io.mateu.workflow.application.services;

import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reveals the value of one sensitive variable to an operator who may see it — the only way a sensitive
 * value leaves the engine in clear through the console, the REST API or the MCP tools.
 *
 * <p>Allowed to a caller holding one of {@code workflow.security.sensitive.reveal-roles} (none by default:
 * nobody can), who may read the process (flow authorization), and gives a reason. Every reveal leaves an
 * operator line in the process's log ({@code [reveal-variable] by <subject>: <name> — reason: …}) and
 * counts on {@code eventconductor.processes.operations{operation=reveal-variable}}; a refused one leaves
 * a line too. The line names the variable, never its value.
 */
@Service
@Slf4j
public class SensitiveVariableRevealService {

    /** The operation's name in the audit line and the metric. */
    public static final String REVEAL_VARIABLE = "reveal-variable";

    private final ProcessOperationsService processes;
    private final OperatorAudit audit;
    private final List<String> revealRoles;

    public SensitiveVariableRevealService(ProcessOperationsService processes, OperatorAudit audit,
                                          @Value("${workflow.security.sensitive.reveal-roles:}") List<String> revealRoles) {
        this.processes = processes;
        this.audit = audit;
        this.revealRoles = revealRoles == null ? List.of()
                : revealRoles.stream().filter(r -> r != null && !r.isBlank()).map(String::trim).toList();
    }

    /** The roles that may reveal; empty means nobody. */
    public List<String> revealRoles() {
        return revealRoles;
    }

    /** Whether this caller holds a reveal role. */
    public boolean mayReveal(AuthorizationContext caller) {
        return caller != null && !revealRoles.isEmpty() && caller.roles().stream().anyMatch(revealRoles::contains);
    }

    /**
     * The value of {@code name} in the process, in clear.
     *
     * @throws IllegalArgumentException          no reason, or {@code name} is not a sensitive variable of the process
     * @throws ProcessOperationsService.ProcessNotFoundException no such process, or it holds no such variable
     * @throws FlowAuthorizationDeniedException  the caller may not read the process, or holds no reveal role
     */
    public String reveal(String processId, String name, String reason, AuthorizationContext caller) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to reveal a sensitive variable.");
        }
        InputLimits.checkText(reason, "a reveal's reason");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name the variable to reveal.");
        }
        var process = processes.get(processId, caller);
        if (!SensitiveVariableMasking.isSensitive(process, name)) {
            throw new IllegalArgumentException("'" + name + "' is not a sensitive variable of process '" + processId
                    + "': its value is shown as it is.");
        }
        var subject = caller == null ? null : caller.subject();
        if (!mayReveal(caller)) {
            audit.refused(processId, null, REVEAL_VARIABLE, subject, reason,
                    name + ": the caller holds none of the reveal roles (workflow.security.sensitive.reveal-roles)");
            throw new FlowAuthorizationDeniedException("Revealing '" + name + "' needs one of the roles "
                    + (revealRoles.isEmpty() ? "configured in workflow.security.sensitive.reveal-roles (none is)" : revealRoles) + ".");
        }
        var variable = process.getVariables().stream()
                .filter(v -> v != null && name.equals(v.name()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ProcessOperationsService.ProcessNotFoundException(
                        "Process '" + processId + "' holds no variable '" + name + "'."));
        audit.performed(process, null, REVEAL_VARIABLE, subject, reason, name);
        return variable.value();
    }
}
