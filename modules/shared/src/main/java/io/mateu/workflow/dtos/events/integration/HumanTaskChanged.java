package io.mateu.workflow.dtos.events.integration;

import java.time.Instant;
import java.util.List;

/**
 * A human task — a form someone has to fill in — was opened, completed or cancelled. The forms
 * engine publishes it on its {@code humanTasks} binding (topic {@code human-tasks} in the standalone
 * app) for whoever keeps people's work in one place: a task is one more thing waiting for a person,
 * next to the notifications that link to a screen. The engine does not read it.
 *
 * @param taskId        the task (the form execution) — the same id on every change of one task
 * @param status        {@code PENDING}, {@code ASSIGNED}, {@code COMPLETED} or {@code CANCELLED}
 * @param requiredRoles who may work on it, as its form requires when the task changes; empty means
 *                      anyone who may use the forms. A snapshot, for routing: whether a person may
 *                      actually complete it is still decided by the forms engine when they try
 * @param userId        who holds it or completed it, when someone does
 */
public record HumanTaskChanged(String taskId,
                               String formId,
                               String formName,
                               String processId,
                               String stepId,
                               String status,
                               List<String> requiredRoles,
                               String userId,
                               Instant at) {
}
