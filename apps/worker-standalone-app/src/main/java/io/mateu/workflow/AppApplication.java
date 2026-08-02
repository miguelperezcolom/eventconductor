package io.mateu.workflow;

import io.mateu.workflow.infra.in.async.WorkerKafkaConsumerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Minimal Kafka worker: it does NOT run the workflow engine. It only imports the worker's
 * Kafka consumer (which completes every requested task) — component scanning is pinned to an
 * empty package so the engine's UI/JPA beans are not pulled in. Runs in workflow.mode=kafka.
 */
@SpringBootApplication(scanBasePackages = "io.mateu.workflow.worker.__none__")
@Import(WorkerKafkaConsumerConfig.class)
public class AppApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppApplication.class, args);
    }

}
