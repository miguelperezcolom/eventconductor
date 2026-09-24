package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Tells the world a human task changed — opened, completed, cancelled — so that it can be shown
 * where a person's work is kept (an inbox, a notification centre) without asking the forms engine.
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

    final StreamBridge streamBridge;
    final FormRepository formRepository;
    final Clock clock = Clock.systemUTC();

    public void changed(FormExecution task) {
        try {
            var form = task.formId() == null ? null : formRepository.findById(task.formId()).orElse(null);
            var event = new HumanTaskChanged(task.id(), task.formId(), form == null ? task.formId() : form.name(),
                    task.processId(), task.stepId(), task.status() == null ? null : task.status().name(),
                    form == null ? List.of() : form.requiredRoles(), task.userId(), clock.instant());
            if (!streamBridge.send(BINDING, event)) {
                log.warn("The broker did not take the change of task {} to {}; inboxes will lag", task.id(), task.status());
            }
        } catch (RuntimeException e) {
            log.warn("Could not publish the change of task {} to {}; inboxes will lag", task.id(), task.status(), e);
        }
    }
}
