package io.mateu.workflow.embedded;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The engine, its UI and a couple of workflows, in one command:
 *
 * <pre>mvn -f testbench/workflow-embedded spring-boot:run</pre>
 *
 * <p>Then http://localhost:8095 — Workflow → Processes → View on a running one. This exists to
 * try the UI by hand: the graph on a live process, the Errors tab, pause/resume, retry.
 *
 * <p>{@code @WorkflowEmbeddedApplication} narrows the scan to what embedded mode needs; the two
 * JPA annotations bring the engine's own entities and repositories back in, which persistence
 * requires and the scan would otherwise leave out.
 */
@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class EmbeddedApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedApplication.class, args);
    }

}
