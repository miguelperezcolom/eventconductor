package io.mateu.e2ejpa.durability;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Boots the engine in embedded mode with JPA persistence for the durability suite.
 *
 * <p>Deliberately placed OUTSIDE {@code io.mateu.workflow} so the memory-mode suite (whose
 * component scan covers {@code io.mateu.workflow}) does not pick this JPA boot config up and
 * try to bootstrap JPA repositories where there is no database. The engine's entities and
 * repositories live in {@code io.mateu.workflow}, so they are scanned explicitly here.
 *
 * <p>And in a package of its own, because {@code @WorkflowEmbeddedApplication} now scans the
 * package its own class sits in — the whole point of it. Two boot applications sharing a package
 * therefore scan each other, and the second {@code @EnableJpaRepositories} to be processed fails
 * with a {@code BeanDefinitionOverrideException} on every engine repository. One application per
 * package is what keeps them apart.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
public class JpaE2eTestApplication {
}
