package io.mateu.workflow.application.updates;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.BusinessKeyPolicy;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.springframework.stereotype.Component;

/**
 * Update-with-start under the definition's {@code businessKeyPolicy} (2.33), replacing
 * {@link DefaultUpdateWithStartPolicy}:
 *
 * <ul>
 *   <li>no process holds the key → start one, the update its first act;</li>
 *   <li>a process of another definition holds it → conflict (409), as before — never reused across definitions;</li>
 *   <li>a process of this definition holds it and has not ended → the update goes to it, whatever the policy
 *       ({@code terminateRunning} included: an update-with-start asks to talk to the running process, not to replace
 *       it); a CONTINUE_AS_NEW chain's holder is its latest run, so the update reaches the run that is alive;</li>
 *   <li>it has ended → a new run takes the key when the policy allows reuse ({@code allowAfterFinished}, a failed run
 *       under {@code allowAfterFailed}, {@code terminateRunning}); under {@code unique} the update goes to the ended
 *       process, which refuses it (409 {@code NOT_ACCEPTING}) as before.</li>
 * </ul>
 *
 * The start goes through {@code CreateProcessUseCase}, which hands the key over (releasing it on the ended run)
 * under the same policy.
 */
@Component
public class BusinessKeyPolicyUpdateWithStartPolicy implements UpdateWithStartPolicy {

    private final ProcessRepository processRepository;

    public BusinessKeyPolicyUpdateWithStartPolicy(ProcessRepository processRepository) {
        this.processRepository = processRepository;
    }

    @Override
    public Resolution resolve(WorkflowDefinition definition, String businessKey) {
        var holder = processRepository.findByBusinessKey(businessKey).orElse(null);
        if (holder == null) {
            return new Start();
        }
        if (!definition.id().equals(holder.getWorkflowDefinitionId())) {
            return new Conflict("Business key '" + businessKey + "' belongs to a process of '"
                    + holder.getWorkflowDefinitionId() + "', not of '" + definition.id() + "'.");
        }
        if (!BusinessKeyPolicy.ended(holder.getStatus())) {
            return new Existing(holder);
        }
        return definition.keyPolicy().decide(holder.getStatus()) == BusinessKeyPolicy.Decision.KEEP
                ? new Existing(holder) : new Start();
    }
}
