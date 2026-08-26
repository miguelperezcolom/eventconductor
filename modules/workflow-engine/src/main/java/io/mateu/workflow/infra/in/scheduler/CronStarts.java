package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Single cron scan shared by both scheduler variants (memory and JPA). For every ACTIVE,
 * non-draft definition with a cron expression, publishes one {@link ProcessCreationRequested}
 * per occurrence elapsed since the last scan.
 *
 * <p>The business key is derived deterministically from the definition id and the occurrence
 * time, so redeliveries and concurrent pods (whose in-memory last-fire clocks differ) collapse
 * into a single process instance through the creation idempotency guard.
 *
 * <p>Occurrences are tracked from scheduler start: schedules missed while no node was running
 * are skipped, not replayed.
 */
@Slf4j
class CronStarts {

    static final DateTimeFormatter BUSINESS_KEY_TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    static void fireDue(WorkflowDefinitionRepository workflowDefinitionRepository,
                        IngressRouter ingressRouter,
                        Map<String, LocalDateTime> lastFireTimes,
                        LocalDateTime now) {
        for (var definition : workflowDefinitionRepository.findAll()) {
            if (!isScheduled(definition)) {
                continue;
            }
            CronExpression cron;
            try {
                cron = CronExpression.parse(definition.cronExpression().trim());
            } catch (IllegalArgumentException e) {
                log.error("Invalid cron expression '{}' on workflow definition '{}', skipping",
                        definition.cronExpression(), definition.id());
                continue;
            }
            var last = lastFireTimes.computeIfAbsent(definition.id(), id -> now);
            var next = cron.next(last);
            while (next != null && !next.isAfter(now)) {
                var businessKey = definition.id() + "-cron-" + next.format(BUSINESS_KEY_TIMESTAMP);
                log.info("Cron start for workflow definition '{}' (occurrence {})", definition.id(), next);
                // The scheduler is the engine acting for itself, not a caller: SYSTEM says so, so a
                // definition with flow-authorization requirements still runs on its schedule.
                ingressRouter.route(new ProcessCreationRequested(definition.id(), businessKey, List.of(),
                        null, io.mateu.workflow.security.AuthorizationContext.SYSTEM));
                last = next;
                next = cron.next(last);
            }
            lastFireTimes.put(definition.id(), last);
        }
    }

    private static boolean isScheduled(WorkflowDefinition definition) {
        return definition.status().accceptsNewInstances()
                && definition.cronExpression() != null
                && !definition.cronExpression().isBlank();
    }

    private CronStarts() {
    }
}
