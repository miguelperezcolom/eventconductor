package io.mateu.workflowbench.orchestrator;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * An orchestrator pod, wired exactly as apps/orchestrator-standalone-app is.
 *
 * <p>The harness lives in {@code io.mateu.workflowbench}, outside the engine's own package, and
 * that placement is load-bearing rather than tidy: the engine component-scans
 * {@code io.mateu.workflow}, so a configuration class of ours sitting inside it gets picked up by
 * every context the harness starts — including the worker, which then tries to build an entity
 * manager for a database it does not have.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
public class BenchmarkOrchestratorApp {

    /** Stands in for the MeterRegistry the standalone app gets from Actuator. */
    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
