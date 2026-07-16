package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowMetricsAutoConfiguration.class,
                    WorkflowEngineAutoConfiguration.class));

    @Test
    void withoutMeterRegistryFallsBackToNoop() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowMetrics.class);
            assertThat(context.getBean(WorkflowMetrics.class))
                    .isNotInstanceOf(MicrometerWorkflowMetrics.class);
        });
    }

    @Test
    void withMeterRegistryProvidesMicrometerImplementation() {
        runner.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowMetrics.class);
                    assertThat(context.getBean(WorkflowMetrics.class))
                            .isInstanceOf(MicrometerWorkflowMetrics.class);
                });
    }

    @Test
    void userDefinedWorkflowMetricsBeanWins() {
        var custom = mock(WorkflowMetrics.class);
        runner.withBean(SimpleMeterRegistry.class)
                .withBean(WorkflowMetrics.class, () -> custom)
                .run(context -> assertThat(context.getBean(WorkflowMetrics.class)).isSameAs(custom));
    }

    @Test
    void registersRunningProcessesGaugeWhenProcessRepositoryPresent() {
        var processRepository = mock(ProcessRepository.class);
        when(processRepository.countByStatus(ProcessStatus.RUNNING)).thenReturn(7L);

        runner.withBean(SimpleMeterRegistry.class)
                .withBean(ProcessRepository.class, () -> processRepository)
                .run(context -> {
                    var registry = context.getBean(MeterRegistry.class);
                    var gauge = registry.get(MicrometerWorkflowMetrics.PROCESSES_RUNNING).gauge();
                    assertThat(gauge.value()).isEqualTo(7);
                });
    }

    @Test
    void skipsRunningProcessesGaugeWithoutProcessRepository() {
        runner.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    var registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(MicrometerWorkflowMetrics.PROCESSES_RUNNING).gauge()).isNull();
                });
    }

    @Test
    void registersPendingOutboxGaugeWhenOutboxRepositoryPresent() {
        var outboxRepository = mock(OutboxMessageEntityRepository.class);
        when(outboxRepository.countByStatus("Pending")).thenReturn(3L);

        runner.withBean(SimpleMeterRegistry.class)
                .withBean(OutboxMessageEntityRepository.class, () -> outboxRepository)
                .run(context -> {
                    var registry = context.getBean(MeterRegistry.class);
                    var gauge = registry.get(MicrometerWorkflowMetrics.OUTBOX_PENDING).gauge();
                    assertThat(gauge.value()).isEqualTo(3);
                });
    }

    @Test
    void skipsPendingOutboxGaugeWithoutOutboxRepository() {
        runner.withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    var registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(MicrometerWorkflowMetrics.OUTBOX_PENDING).gauge()).isNull();
                });
    }
}
