package io.mateu.workflowdist.support.orchestrator;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Boots a full orchestrator instance for the distributed suite. Despite the annotation's
 * name it just scans the engine's {@code io.mateu.workflow} tree (UI adapters excluded);
 * the tests run it with {@code workflow.mode=kafka} + {@code workflow.persistence=jpa},
 * which activates the Kafka consumers, the outbox relay, the JDBC advisory locks and the
 * JPA repositories — the same wiring as apps/orchestrator-standalone-app.
 *
 * <p>Lives outside {@code io.mateu.workflow} so orchestrator component scanning never
 * picks up the suite's own configuration classes — which is also why the JPA entity and
 * repository packages must be pointed at the engine explicitly.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
public class DistOrchestratorApp {

    /** Stands in for the MeterRegistry the standalone app gets from Actuator. */
    @org.springframework.context.annotation.Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }
}
