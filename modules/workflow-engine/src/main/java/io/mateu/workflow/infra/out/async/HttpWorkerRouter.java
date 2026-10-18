package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.httpworkers.HttpTaskInbox;
import io.mateu.workflow.application.httpworkers.HttpTaskRouting;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Takes the tasks of the HTTP-served topics before the mode's own publisher (embedded or Kafka)
 * sees them, so HTTP workers serve some topics while the embedded and Kafka workers keep serving the
 * rest. Both {@code DownstreamEventPublisher}s ask it first; it exists only when
 * {@code workflow.workers.http.enabled} is true.
 *
 * <ul>
 *   <li>A {@link TaskExecutionRequested} on a routed topic is queued for HTTP workers.</li>
 *   <li>A {@link TaskCancellationRequested} is offered to the queue — the queue knows whether it holds
 *       the task. It is left to the mode's publisher when the queue did not and the topic is not an
 *       explicitly HTTP one, so a task that went to a Kafka worker still gets its cancellation.</li>
 * </ul>
 */
public class HttpWorkerRouter {

    private final HttpTaskRouting routing;
    private final HttpTaskInbox inbox;

    public HttpWorkerRouter(HttpTaskRouting routing, HttpTaskInbox inbox) {
        this.routing = routing;
        this.inbox = inbox;
    }

    /** @return true when the event was taken here and the mode's publisher must not send it */
    public boolean route(DomainEvent event, String topic) {
        if (event instanceof TaskExecutionRequested task && routing.routesTask(topic, task.taskId())) {
            inbox.dispatched(task, topic);
            return true;
        }
        if (event instanceof TaskCancellationRequested cancellation) {
            return inbox.cancelled(cancellation.taskId()) || routing.namesTopic(topic);
        }
        return false;
    }

    /** The publishers' one line: whether a router exists and took the event. */
    static boolean taken(ObjectProvider<HttpWorkerRouter> router, DomainEvent event, String topic) {
        if (router == null) {
            return false;
        }
        var found = router.getIfAvailable();
        return found != null && found.route(event, topic);
    }
}
