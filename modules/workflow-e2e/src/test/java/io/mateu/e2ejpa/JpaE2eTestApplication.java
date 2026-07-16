package io.mateu.e2ejpa;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Boots the engine in embedded mode with JPA persistence for the durability suite.
 *
 * <p>Deliberately placed OUTSIDE {@code io.mateu.workflow} so the memory-mode suite (whose
 * component scan covers {@code io.mateu.workflow}) does not pick this JPA boot config up and
 * try to bootstrap JPA repositories where there is no database. The engine's entities and
 * repositories live in {@code io.mateu.workflow}, so they are scanned explicitly here.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
public class JpaE2eTestApplication {
}
