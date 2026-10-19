package io.mateu.workflowbench.suite.jpa;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflowbench.suite.SuiteEngineBeans;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The engine with JPA persistence: embedded/jpa, and every orchestrator pod of the kafka modes.
 *
 * <p>In a package of its own because {@code @WorkflowEmbeddedApplication} scans the package its
 * class sits in, and two applications sharing one would scan each other.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
@Import(SuiteEngineBeans.class)
public class SuiteJpaApp {
}
