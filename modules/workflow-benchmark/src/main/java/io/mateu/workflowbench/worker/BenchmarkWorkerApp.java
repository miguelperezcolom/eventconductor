package io.mateu.workflowbench.worker;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.WorkerReply;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;

import java.util.function.Consumer;

/**
 * A worker whose only work is waiting.
 *
 * <p>Its think time is the dial that separates the two questions a throughput number usually
 * blurs together. At zero, what is left in the measurement is the engine. Turn it up and the
 * figure becomes what your workers can get through — which is the real ceiling in production, and
 * not the engine's to answer.
 *
 * <p>It replies through {@link WorkerReply}, which echoes the process back so the event routes to
 * the pod that owns it — and, more to the point, refuses to let a reply be dropped. The first
 * version of this class sent the reply and ignored the result, and during a ninety-second broker
 * outage that quietly lost 3 352 of them: 3 356 processes left waiting forever for an answer that
 * was never published. It was the harness's bug, not the engine's, but it was also the pattern
 * every worker in this repository used.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class})
public class BenchmarkWorkerApp {

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent(StreamBridge streamBridge) {
        var thinkMillis = Integer.getInteger("bench.worker.think-ms", 0);
        return event -> {
            if (!(event instanceof TaskExecutionRequested task)) {
                return;
            }
            if (thinkMillis > 0) {
                try {
                    Thread.sleep(thinkMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            WorkerReply.completed(streamBridge, task, task.variables());
        };
    }
}
