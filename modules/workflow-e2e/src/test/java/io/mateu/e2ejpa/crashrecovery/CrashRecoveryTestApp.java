package io.mateu.e2ejpa.crashrecovery;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.e2ejpa.durability.JpaE2eTestApplication;
import io.mateu.workflow.e2e.support.E2eConfig;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Boot application for the crash-recovery test. Placed outside {@code io.mateu.workflow} (see
 * {@link JpaE2eTestApplication}) and scans the engine's JPA entities/repositories explicitly.
 *
 * <p>In a package of its own for the reason spelled out there: two boot applications sharing a
 * package now scan each other, and their {@code @EnableJpaRepositories} collide.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
@Import(E2eConfig.class)
public class CrashRecoveryTestApp {
}
