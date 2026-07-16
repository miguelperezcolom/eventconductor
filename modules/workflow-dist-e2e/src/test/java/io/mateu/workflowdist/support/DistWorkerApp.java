package io.mateu.workflowdist.support;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;

import java.util.function.Consumer;

/**
 * Standalone Kafka worker for the distributed suite — the same contract as
 * modules/sample-worker: consumes {@link TaskExecutionRequested} from the {@code downstream}
 * topic and reports {@code TaskStatusChanged} back on {@code upstream}, but with per-step
 * programmable behavior and execution counting (see {@link WorkerStub}). No database.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class})
public class DistWorkerApp {

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent(StreamBridge streamBridge) {
        WorkerStub.attach(streamBridge);
        return event -> {
            if (event instanceof TaskExecutionRequested request) {
                WorkerStub.onTask(request);
            }
        };
    }
}
