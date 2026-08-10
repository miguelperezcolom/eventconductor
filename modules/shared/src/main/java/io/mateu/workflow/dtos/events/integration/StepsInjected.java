package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * A DYNAMIC step's worker asking to add new steps to the running process.
 *
 * <p>The reply of a DYNAMIC step may carry this: the engine loads the injecting step execution,
 * validates the batch and materialises the steps into the same process, add-only. Only a DYNAMIC
 * step may inject — the message is rejected for any other type.
 *
 * @param taskExecutionId the DYNAMIC step execution doing the injecting. Also the idempotency key:
 *                        a re-delivered message must not inject twice, so the use case skips when
 *                        that step already has injected children.
 * @param processId       echoed back so the injection lands on the pod that owns the process, the
 *                        same single-writer routing {@link TaskExecutionRequested} and
 *                        {@link TaskStatusChanged} rely on. Never null here — a DYNAMIC step
 *                        always knows its process.
 * @param stepsJson       a JSON array of step objects, in the same schema a workflow definition
 *                        uses. Carried as a string because this leaf module cannot reference the
 *                        engine's (large, UI-annotated) domain {@code Step}.
 */
public record StepsInjected(String taskExecutionId, String processId, String stepsJson) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
