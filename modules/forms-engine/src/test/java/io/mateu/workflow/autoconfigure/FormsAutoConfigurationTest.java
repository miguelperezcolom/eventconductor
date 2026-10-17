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
}
