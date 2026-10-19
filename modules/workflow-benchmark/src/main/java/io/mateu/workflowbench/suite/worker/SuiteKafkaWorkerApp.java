package io.mateu.workflowbench.suite.worker;

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

import java.util.List;
import java.util.function.Consumer;

/**
 * The no-op worker for the kafka modes: the same behaviour as the embedded one (complete at once;
 * a step whose id starts with {@code fail} fails for good), but over the broker, replying through
 * {@link WorkerReply} like any real worker.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class})
public class SuiteKafkaWorkerApp {

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent(StreamBridge streamBridge) {
        return event -> {
            if (!(event instanceof TaskExecutionRequested task)) {
                return;
            }
            if (task.stepId() != null && task.stepId().startsWith("fail")) {
                WorkerReply.failed(streamBridge, task, List.of(), "suite: this step always fails");
            } else {
                WorkerReply.completed(streamBridge, task, List.of());
            }
        };
    }
}
