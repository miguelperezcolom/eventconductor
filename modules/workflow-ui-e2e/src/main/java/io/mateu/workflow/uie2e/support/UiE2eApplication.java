package io.mateu.workflow.uie2e.support;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflow.schema.ManagedSchema;
import io.mateu.workflow.schema.ManagedSchemaInitializer;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;

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

    /**
     * Applies the engine's own migrations explicitly, which it should be doing for itself.
     *
     * <p><b>This is a workaround for a defect, not a pattern to copy.</b> In this application
     * {@code WorkflowSchemaAutoConfiguration} loads but its initializer is conditioned away:
     * {@code @ConditionalOnSingleCandidate(DataSource.class)} finds no bean at the moment it is
     * evaluated, even though {@code DataSourceAutoConfiguration} matches, which means the
     * configuration is being ordered ahead of the data source rather than after it. The engine
     * noticed and said so — its unmanaged-schema warning fired — and then Hibernate's
     * {@code ddl-auto=validate} failed on the missing tables, which is exactly the sequence that
     * feature was built to produce instead of silence.
     *
     * <p>Declaring the initializer here restores the real behaviour: the same migrations, from the
     * same location, so these tests run on the schema production gets rather than on whatever
     * {@code ddl-auto} would build. The engine's own
     * {@code EntityManagerFactoryDependsOnPostProcessor} keys on this type, so providing the bean
     * is enough to order it before Hibernate.
     *
     * <p>Remove this the moment the engine applies its schema here on its own — and the way to
     * know is that these tests keep passing without it.
     */
    @Bean
    ManagedSchemaInitializer uiE2eSchema(DataSource dataSource) {
        return new ManagedSchemaInitializer(
                new ManagedSchema("workflow", "classpath:db/migration/workflow",
                        "eventconductor_schema_history"),
                dataSource);
    }
}
