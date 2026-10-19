package io.mateu.workflow.application.updates;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

/**
 * Where an update-with-start goes for a business key: to a process that already has it, to a new one,
 * or nowhere. The one decision about business keys update-with-start makes, kept behind this interface
 * so a business-key policy (keys reused once a process finishes, continue-as-new chains) can replace
 * {@link DefaultUpdateWithStartPolicy} without touching the update path. A bean of this type overrides it.
 */
public interface UpdateWithStartPolicy {

    Resolution resolve(WorkflowDefinition definition, String businessKey);

    /** What the policy decided. */
    sealed interface Resolution permits Existing, Start, Conflict {
    }

    /** Deliver the update to this process (it may still refuse it: finished, not accepting). */
    record Existing(Process process) implements Resolution {
    }

    /** Start a new process with the key and deliver the update to it as its first act. */
    record Start() implements Resolution {
    }

    /** Neither: the key cannot be used for this definition (409). */
    record Conflict(String why) implements Resolution {
    }
}
