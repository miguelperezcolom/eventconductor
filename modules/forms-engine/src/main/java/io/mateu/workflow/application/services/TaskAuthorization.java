package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.security.FlowAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may work on a task, from the {@code requiredScopes}/{@code requiredRoles} of the form the task
 * asks to be filled in.
 *
 * <p>Three places have to agree and all three go through here: what a person's task list shows, what
 * they may claim, and what they may complete. <b>The listing filter is not the boundary</b> — a task
 * id travels in a URL, in a log line, in a link somebody pastes — so claim and complete check again
 * rather than trusting that an id could only have come from a list this person was allowed to see.
 * The filter exists so that nobody is shown work they cannot do; the checks exist so that seeing it
 * would not have helped.
 *
 * <p>Off unless {@code workflow.security.flow-authorization.enabled}, and when off nothing here
 * narrows anything — which is exactly how every deployment behaved before forms could declare
 * requirements at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskAuthorization {

    final CallerResolver callerResolver;
    final FormRepository formRepository;

    @Value("${workflow.security.flow-authorization.enabled:false}")
    boolean enabled;

    public boolean enabled() {
        return enabled;
    }

    /**
     * The ids of the forms the person on the other end of this request may work on.
     *
     * <p>Resolved by reading the catalogue and asking of each form, rather than by copying the
     * requirements onto every task row when it is created: the catalogue is small and cached, and a
     * snapshot would keep answering with the rules that applied the day the task was raised — so
     * tightening a form would leave every task already waiting under the old rule, which is the
     * opposite of what tightening it was for.
     *
     * <p>An empty set means <b>none</b>, not "no restriction". Callers must treat it as such.
     */
    public Set<String> permittedFormIds() {
        var caller = callerResolver.current();
        return formRepository.findAll().stream()
                .filter(form -> FlowAuthorizationService
                        .authorize(caller, form.requiredScopes(), form.requiredRoles())
                        .allowed())
                .map(Form::id)
                .collect(Collectors.toSet());
    }

    /**
     * Refuses the current caller if the form requires anything they do not hold.
     *
     * @param action what they were trying to do, for the message — e.g. {@code "claim"}
     * @param form   the form the task asks to be filled in; {@code null} carries no requirements,
     *               because a form that is not in the catalogue cannot state any (the submission
     *               path refuses such a task on its own account, and for its own reasons)
     * @throws FlowAuthorizationDeniedException if anything required is missing
     */
    public void refuseIfCallerMayNot(String action, Form form, String taskId) {
        if (!enabled || form == null) {
            return;
        }
        var caller = callerResolver.current();
        var decision = FlowAuthorizationService.authorize(caller, form.requiredScopes(), form.requiredRoles());
        if (!decision.allowed()) {
            log.warn("Refused to {} task {}: caller '{}' is missing scopes {} and roles {} required by form '{}'",
                    action, taskId, caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles(), form.name());
            throw FlowAuthorizationDeniedException.of(
                    action + " task " + taskId,
                    caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles());
        }
    }
}
