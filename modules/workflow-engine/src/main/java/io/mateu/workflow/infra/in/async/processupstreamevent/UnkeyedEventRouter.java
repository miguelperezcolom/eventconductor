package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Sends an event that arrived without a partition key back out with one, so it reaches the pod
 * that owns the process instead of being handled wherever it landed.
 *
 * <p>Only one thing produces such events: a worker built against a shared module older than the
 * one that added {@code processId} to {@link TaskStatusChanged}. That fallback is deliberate —
 * third-party workers must keep working — but in kafka mode it is the last way two pods can end
 * up on the same process, and since the pessimistic lock is gone there, "handled wherever it
 * landed" is no longer safe. Two step-overs reading the same state and writing different rows
 * collide on no version and would dispatch a step twice.
 *
 * <p>Costs one indexed lookup and one extra hop, paid only by workers that do not echo the
 * process. A worker on a current shared module never comes through here.
 *
 * <p>Inert outside kafka mode: with no partitions there is nothing to route to, and the extra
 * hop would buy nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnkeyedEventRouter {

    final StepExecutionRepository stepExecutionRepository;

    /**
     * Lazy to break a cycle that only exists in embedded mode, where the upstream publisher
     * dispatches back into the engine: handler → router → publisher → upstream use case →
     * handler. In kafka mode the publisher goes to the broker and there is no cycle — and this
     * router does nothing there anyway.
     */
    @Lazy
    @Autowired
    UpstreamEventPublisher upstreamEventPublisher;

    @Value("${workflow.mode:embedded}")
    String mode;

    /**
     * Returns true when the event was re-published keyed and the caller must not handle it.
     */
    public boolean rerouted(TaskStatusChanged event) {
        if (!"kafka".equals(mode) || event.processId() != null) {
            return false;
        }
        var processId = stepExecutionRepository.findById(event.taskExecutionId())
                .map(stepExecution -> stepExecution.getProcessId())
                .orElse(null);
        if (processId == null) {
            // Nothing to route to. Handling it here is what happened before ownership existed,
            // and a report for a step that does not exist is dropped by the use case anyway.
            return false;
        }
        log.debug("Re-routing unkeyed status report for step {} to the owner of process {}",
                event.taskExecutionId(), processId);
        upstreamEventPublisher.publish(new TaskStatusChanged(
                event.taskExecutionId(), event.status(), event.variables(), processId));
        return true;
    }

    /**
     * The same single-writer guard for a {@link io.mateu.workflow.dtos.events.integration.StepsInjected}
     * reply. A DYNAMIC step always echoes its process, so this event is never unkeyed and this only
     * ever returns false — the method exists so the handler reads exactly like the others and the
     * routing symmetry is honoured, not because there is a legacy worker to route back.
     */
    public boolean rerouted(io.mateu.workflow.dtos.events.integration.StepsInjected event) {
        if (!"kafka".equals(mode) || event.processId() != null) {
            return false;
        }
        var processId = stepExecutionRepository.findById(event.taskExecutionId())
                .map(stepExecution -> stepExecution.getProcessId())
                .orElse(null);
        if (processId == null) {
            return false;
        }
        log.debug("Re-routing unkeyed steps-injected for step {} to the owner of process {}",
                event.taskExecutionId(), processId);
        upstreamEventPublisher.publish(new io.mateu.workflow.dtos.events.integration.StepsInjected(
                event.taskExecutionId(), processId, event.stepsJson()));
        return true;
    }
}
