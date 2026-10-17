package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.infra.in.grpc.RuleGrpcServer;
import io.mateu.workflow.infra.in.grpc.RuleGrpcServerConfiguration;
import io.mateu.workflow.schema.ManagedSchemaInitializer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the rules engine wires into a host, and the switches that turn each piece off. */
class RulesAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleCatalogMetricsAutoConfiguration.class,
                    RulesEngineAutoConfiguration.class));

    @Test
    void withTheCatalogInTheSameJvmTheRuntimeReadsItLocally() {
        runner.withBean(RuleRepository.class, () -> mock(RuleRepository.class))
                .run(context -> assertThat(context).hasSingleBean(RuleSource.class));
    }

    @Test
    void aRemoteRuleSourceIsNotReplacedByTheLocalOne() {
        runner.withPropertyValues("rules.source=remote")
                .withBean(RuleRepository.class, () -> mock(RuleRepository.class))
                .run(context -> assertThat(context).doesNotHaveBean(RuleSource.class));
    }

    @Test
    void withoutAMeterRegistryMetricsAreANoOp() {
        runner.run(context -> assertThat(context.getBean(RuleCatalogMetrics.class))
                .isSameAs(RuleCatalogMetrics.NOOP));
    }

    @Test
    void withAMeterRegistryCatalogMetricsAreRecorded() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context.getBean(RuleCatalogMetrics.class))
                        .isInstanceOf(MicrometerRuleCatalogMetrics.class));
    }

    @Test
    void theRulesSchemaIsAppliedUnlessTurnedOff() {
        var schema = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RulesSchemaAutoConfiguration.class));
        schema.run(context -> {
            assertThat(context).hasSingleBean(ManagedSchemaInitializer.class);
            assertThat(context.getBean(RulesSchemaProperties.class).getTable())
                    .isEqualTo("eventconductor_schema_history_rules");
            assertThat(context.getBean(RulesSchemaProperties.class).isEnabled()).isTrue();
        });
        schema.withPropertyValues("rules.schema.table=my_history")
                .run(context -> assertThat(context.getBean(RulesSchemaProperties.class).getTable())
                        .isEqualTo("my_history"));
        schema.withPropertyValues("rules.schema.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ManagedSchemaInitializer.class));
    }

    @Test
    void theGrpcServerIsOnlyWiredWhenEnabled() {
        var grpc = new ApplicationContextRunner()
                .withUserConfiguration(RuleGrpcServerConfiguration.class)
                .withBean(RuleRepository.class, () -> mock(RuleRepository.class))
                .withBean(RuleJsonMapper.class, () -> mock(RuleJsonMapper.class))
                .withBean(RuleCatalogMetrics.class, () -> RuleCatalogMetrics.NOOP);
        grpc.run(context -> assertThat(context).doesNotHaveBean(RuleGrpcServer.class));
        // Port 0: the lifecycle starts it on any free port and the context closes it.
        grpc.withPropertyValues("rules.grpc.enabled=true", "rules.grpc.port=0")
                .run(context -> assertThat(context.getBean(RuleGrpcServer.class).isRunning()).isTrue());
    }
}
