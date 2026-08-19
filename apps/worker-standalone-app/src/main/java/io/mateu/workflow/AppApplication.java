package io.mateu.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The test worker: a worker that does no work, and plays back the scenario it was asked for.
 *
 * <p>It does NOT run the workflow engine, and the absence is the point. The engine under test runs
 * in its own process and talks to this one over Kafka exactly as it would to a real worker, so what
 * a scenario proves here is what would happen in production. An application that embedded the
 * engine would be testing itself.
 *
 * <p>The scan is pointed at {@code io.mateu.testworker} rather than inherited from this class's own
 * package, because the test worker deliberately lives <b>outside</b> {@code io.mateu.workflow}.
 * Everything that scans the engine's tree — {@code @WorkflowEmbeddedApplication}, and every
 * standalone app — would otherwise sweep up a {@code consumeWorkerEvent} binding and two JPA stores
 * from any classpath that happened to contain this module. An orchestrator quietly answering its
 * own tasks is not a failure anyone would enjoy diagnosing.
 *
 * <p>There is no {@code workflow.mode} here, and there should not be: mode is how an application
 * that runs the engine chooses its transport. A worker turns the Kafka binder on by having it on
 * the classpath, and {@code SynchronousProducerDefaults} in {@code shared} gives its replies the
 * producer settings they need without being asked.
 */
@SpringBootApplication(scanBasePackages = "io.mateu.testworker")
@EntityScan("io.mateu.testworker.infra.out.persistence")
@EnableJpaRepositories("io.mateu.testworker.infra.out.persistence")
public class AppApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppApplication.class, args);
    }

}
