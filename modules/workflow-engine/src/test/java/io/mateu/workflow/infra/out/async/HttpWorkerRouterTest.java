package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.httpworkers.HttpTaskInbox;
import io.mateu.workflow.application.httpworkers.HttpTaskRouting;
import io.mateu.workflow.application.httpworkers.HttpTaskSignal;
import io.mateu.workflow.application.httpworkers.HttpWorkerMetrics;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.infra.out.memory.InMemoryHttpTaskQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the downstream publishers hand to the HTTP queue, and what they keep. */
class HttpWorkerRouterTest {

    InMemoryHttpTaskQueue queue = new InMemoryHttpTaskQueue();
    HttpTaskInbox inbox = new HttpTaskInbox(queue, new HttpTaskSignal(), HttpWorkerMetrics.none(), Clock.systemUTC());

    private static TaskExecutionRequested task(String id) {
        return new TaskExecutionRequested(id, "p", "wd", "s", "", List.of(), 1);
    }

    @Test
    void routedTasksAreQueuedAndOthersPassThrough() {
        var router = new HttpWorkerRouter(new HttpTaskRouting(List.of("payments")), inbox);

        assertThat(router.route(task("a"), "payments")).isTrue();
        assertThat(queue.find("a")).isPresent();
        assertThat(queue.find("a").orElseThrow().topic()).isEqualTo("payments");
        assertThat(router.route(task("b"), "shipping")).isFalse();
        assertThat(queue.find("b")).isEmpty();
        assertThat(router.route(new TaskStatusChanged("a", TaskStatus.COMPLETED, List.of()), "payments")).isFalse();
    }

    @Test
    void cancellationsGoWhereTheirTaskWent() {
        var router = new HttpWorkerRouter(new HttpTaskRouting(List.of("payments", "*")), inbox);
        router.route(task("a"), "orders");

        // Held by the queue: taken.
        assertThat(router.route(new TaskCancellationRequested("a"), "orders")).isTrue();
        assertThat(queue.find("a")).isEmpty();
        // Not held, topic only matched by the wildcard: a Kafka/embedded worker may have it.
        assertThat(router.route(new TaskCancellationRequested("x"), "orders")).isFalse();
        // Not held, but the topic is an HTTP one by name: nowhere else to go.
        assertThat(router.route(new TaskCancellationRequested("y"), "payments")).isTrue();
    }

    @Test
    void withoutARouterEverythingPassesThrough() {
        assertThat(HttpWorkerRouter.taken(null, task("a"), "payments")).isFalse();
        var none = new StaticListableBeanFactory().getBeanProvider(HttpWorkerRouter.class);
        assertThat(HttpWorkerRouter.taken(none, task("a"), "payments")).isFalse();

        var factory = new StaticListableBeanFactory();
        factory.addBean("router", new HttpWorkerRouter(new HttpTaskRouting(List.of("payments")), inbox));
        assertThat(HttpWorkerRouter.taken(factory.getBeanProvider(HttpWorkerRouter.class), task("a"), "payments"))
                .isTrue();
    }
}
