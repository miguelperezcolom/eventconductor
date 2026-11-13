package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.infra.config.FormsEngineConfiguration;
import io.mateu.workflow.schema.ManagedSchemaInitializer;
import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the forms engine wires into a host, and the switches that turn each piece off. */
class FormsAutoConfigurationTest {

    final ApplicationContextRunner metrics = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FormsMetricsAutoConfiguration.class,
                    FormsEngineAutoConfiguration.class));

    @Test
    void withoutAMeterRegistryMetricsAreANoOp() {
        metrics.run(context -> assertThat(context.getBean(FormsMetrics.class)).isSameAs(FormsMetrics.NOOP));
    }

    @Test
    void withAMeterRegistryTaskMetricsAreRecorded() {
        metrics.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context.getBean(FormsMetrics.class))
                        .isInstanceOf(MicrometerFormsMetrics.class));
    }

    @Test
    void theFormsSchemaIsAppliedUnlessTurnedOff() {
        var schema = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FormsSchemaAutoConfiguration.class));
        schema.run(context -> {
            assertThat(context).hasSingleBean(ManagedSchemaInitializer.class);
            assertThat(context.getBean(FormsSchemaProperties.class).getTable())
                    .isEqualTo("eventconductor_schema_history_forms");
            assertThat(context.getBean(FormsSchemaProperties.class).isEnabled()).isTrue();
        });
        schema.withPropertyValues("forms.schema.table=my_history")
                .run(context -> assertThat(context.getBean(FormsSchemaProperties.class).getTable())
                        .isEqualTo("my_history"));
        schema.withPropertyValues("forms.schema.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ManagedSchemaInitializer.class));
    }

    @Test
    void theImportProvenanceIsInMemoryUnlessTheHostProvidesOne() {
        var engine = new ApplicationContextRunner().withUserConfiguration(FormsEngineConfiguration.class);
        engine.run(context -> assertThat(context.getBean(ImportedDefinitionsRegistry.class))
                .isInstanceOf(InMemoryImportedDefinitionsRegistry.class));
        var durable = mock(ImportedDefinitionsRegistry.class);
        engine.withBean(ImportedDefinitionsRegistry.class, () -> durable)
                .run(context -> assertThat(context.getBean(ImportedDefinitionsRegistry.class)).isSameAs(durable));
    }

    final ApplicationContextRunner inProcess = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FormsInProcessAutoConfiguration.class))
            .withBean(io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase.class,
                    () -> mock(io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase.class))
            .withBean(io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase.class,
                    () -> mock(io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase.class))
            .withBean(io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase.class,
                    () -> mock(io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase.class))
            .withBean(io.mateu.workflow.application.out.FormExecutionRepository.class,
                    () -> mock(io.mateu.workflow.application.out.FormExecutionRepository.class));

    @Test
    void nextToAnEmbeddedEngineTheFormsEngineTalksToItInProcess() {
        inProcess.run(context -> {
            assertThat(context.getBean(org.springframework.cloud.stream.function.StreamOperations.class))
                    .isInstanceOf(io.mateu.workflow.infra.out.inprocess.InProcessStreamOperations.class);
            assertThat(context).hasSingleBean(io.mateu.workflow.infra.in.inprocess.FormsInProcessTaskRoute.class);
        });
        inProcess.withPropertyValues("workflow.mode=embedded")
                .run(context -> assertThat(context).hasSingleBean(io.mateu.workflow.infra.in.inprocess.FormsInProcessTaskRoute.class));
    }

    @Test
    void inKafkaModeNothingChangesTheBrokerIsUsed() {
        inProcess.withPropertyValues("workflow.mode=kafka").run(context -> {
            assertThat(context).doesNotHaveBean(io.mateu.workflow.infra.out.inprocess.InProcessStreamOperations.class);
            assertThat(context).doesNotHaveBean(io.mateu.workflow.infra.in.inprocess.FormsInProcessTaskRoute.class);
        });
    }

    @Test
    void aStreamBridgeOfItsOwnIsKept() {
        var bridge = mock(org.springframework.cloud.stream.function.StreamOperations.class);
        inProcess.withBean(org.springframework.cloud.stream.function.StreamOperations.class, () -> bridge)
                .run(context -> assertThat(context.getBean(org.springframework.cloud.stream.function.StreamOperations.class))
                        .isSameAs(bridge));
    }

    @Test
    void userTasksCanBeLeftToTheApplicationsExecutor() {
        inProcess.withPropertyValues("forms.embedded.serve-user-tasks=false")
                .run(context -> assertThat(context).doesNotHaveBean(io.mateu.workflow.infra.in.inprocess.FormsInProcessTaskRoute.class));
    }

    @Test
    void withoutTheFormsEnginesUseCasesInTheContextThereIsNoRoute() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FormsInProcessAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(io.mateu.workflow.infra.in.inprocess.FormsInProcessTaskRoute.class);
                });
    }
}
