package io.mateu.e2ejpa;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflow.e2e.support.E2eConfig;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Boot application for the crash-recovery test. Placed outside {@code io.mateu.workflow} (see
 * {@link JpaE2eTestApplication}) and scans the engine's JPA entities/repositories explicitly.
 */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
@Import(E2eConfig.class)
public class CrashRecoveryTestApp {
}
