package io.mateu.workflow.inprocess;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * The engine's {@code downstream} topic when there is no broker, for a module that serves some of the
 * engine's tasks itself — the forms engine taking the {@code complete-form} task of a USER_TASK, and
 * the cancellations and escalations that follow it.
 *
 * <p>In embedded mode every task goes to the application's one {@code EmbeddedTaskExecutor}. The
 * embedded engine offers each downstream event to the routes in the context first (in {@code @Order});
 * the first one to take it keeps it, and the executor never sees it. A route that does not recognise
 * an event returns {@code false} and it carries on as before.
 *
 * <p>Here, in {@code shared}, so that a module can serve tasks of an in-process engine without
 * depending on it.
 */
public interface InProcessTaskRoute {

    /**
     * @param event the downstream event: a {@code TaskExecutionRequested}, a {@code TaskCancellationRequested},
     *              a {@code HumanTaskEscalationRequested} …
     * @param topic the destination it would have been published to, may be null
     * @return true when it was taken here and no one else must handle it
     */
    boolean take(DomainEvent event, String topic);
}
