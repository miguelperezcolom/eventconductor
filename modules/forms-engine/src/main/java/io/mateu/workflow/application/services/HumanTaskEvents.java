package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.time.EngineClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Tells the world a human task changed — created, assigned, claimed, released, completed, cancelled,
 * escalated — so that it can be shown where a person's work is kept (an inbox, a notification centre)
 * or start a workflow of its own (an event trigger on the destination), without asking the forms engine.
 *
 * <p>Published as a binary CloudEvent: the {@link HumanTaskChanged} is the record value, and
 * {@code ce_type} is {@code io.eventconductor.task.<event>} — so an event trigger can match
 * {@code io.eventconductor.task.escalated} on the destination this binding's topic is mapped to.
 * Also counted, as {@code eventconductor.tasks.events} tagged with the event.
 *
 * <p>Best effort, like the timeline's log line: it is published after the task is saved, and a broker
 * that refuses it costs an inbox that lags, never a task that is not created or not closed. The task
 * itself, and the reply to the engine, do not depend on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanTaskEvents {

    public static final String BINDING = "humanTasks";

    /** The CloudEvents source of every task event. */
    public static final String SOURCE = "eventconductor/forms";

    final StreamBridge streamBridge;
    final FormRepository formRepository;
    final FormsMetrics formsMetrics;

    /** {@code event} happened to {@code task}: count it and publish it. */
    public void changed(FormExecution task, String event) {
        formsMetrics.taskEvent(event, task.formId());
        try {
            var form = task.formId() == null ? null : formRepository.findById(task.formId()).orElse(null);
            var at = EngineClock.instant();
            var changed = new HumanTaskChanged(task.id(), task.formId(), form == null ? task.formId() : form.name(),
                    task.processId(), task.stepId(), task.status() == null ? null : task.status().name(),
                    form == null ? List.of() : form.requiredRoles(), task.userId(), at, event,
                    task.candidateUsers(), task.candidateGroups(), task.priorityOrDefault(),
                    task.dueDate() == null ? null : task.dueDate().toInstant(ZoneOffset.UTC),
                    task.escalations(), task.businessKey());
            var message = MessageBuilder.withPayload(changed)
                    .setHeader("ce_specversion", "1.0")
                    .setHeader("ce_id", UUID.randomUUID().toString())
                    .setHeader("ce_source", SOURCE)
                    .setHeader("ce_type", changed.cloudEventType())
                    .setHeader("ce_subject", task.id())
                    .setHeader("ce_time", at.toString())
                    .setHeader("ce_processid", task.processId() == null ? "" : task.processId())
                    .build();
            if (!streamBridge.send(BINDING, message)) {
                log.warn("The broker did not take the {} of task {}; inboxes will lag", event, task.id());
            }
        } catch (RuntimeException e) {
            log.warn("Could not publish the {} of task {}; inboxes will lag", event, task.id(), e);
        }
    }
}
