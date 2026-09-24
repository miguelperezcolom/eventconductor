package io.mateu.e2ejpa.inlinecrash;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflow.e2e.support.E2eConfig;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Boot application for the inline-crash test; in a package of its own, like {@code CrashRecoveryTestApp}. */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
@Import(E2eConfig.class)
public class InlineCrashTestApp {
}
