package io.mateu.workflow.uie2e.support;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The application under test: the engine, its UI and a programmable worker, on a random port.
 *
 * <p>It is deliberately the same shape as {@code testbench/workflow-embedded}, the app used to try
 * the UI by hand — embedded mode, JPA on H2, the engine's own {@code WorkflowMenu} mounted at the
 * root. Anything the UI needs that this does not have is something the testbench app was papering
 * over.
 */
@WorkflowEmbeddedApplication
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class UiE2eApplication {

    @Bean
    EmbeddedTaskExecutor uiTestWorker(UpdateStepExecutionUseCase updateStepExecution) {
        return new UiTestWorker(updateStepExecution);
    }
}
