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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WorkflowMetricsAutoConfiguration.class,
                    WorkflowMetricsFallbackAutoConfiguration.class));

    @Test
    void withoutMeterRegistryDegradesToNoop() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowMetrics.class);
            var metrics = context.getBean(WorkflowMetrics.class);
            // The registry is resolved lazily, so with none present every call resolves nothing and is a
            // no-op that must not throw — the behaviour that matters, whatever the bean's concrete type.
            assertThatCode(() -> {
                metrics.processStarted("orders");
                metrics.stepExecutionFinished("orders", null, java.time.Duration.ofMillis(1));
                metrics.outboxRelayCycle(java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(1));
                metrics.stalledStepsObserved(3);
            }).doesNotThrowAnyException();
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
    void micrometerImplementationWinsWhenBothConfigsAreComponentScanned() {
        // An application whose @SpringBootApplication sits at io.mateu.workflow scans this package and
        // registers both auto-configurations as plain @Configuration, where @AutoConfiguration(before=)
        // ordering is not honoured and the beans resolve in class-name order — WorkflowEngine before
        // WorkflowMetrics — so the no-op used to register first and shadow the Micrometer implementation.
        // Registering them as *user* configurations reproduces that (AutoConfigurations.of would impose
        // the ordering the real app lacks). The no-op is now gated on MeterRegistry being absent, so with
        // it present the Micrometer implementation wins whatever the order.
        new ApplicationContextRunner()
                .withUserConfiguration(WorkflowMetricsAutoConfiguration.class, WorkflowMetricsFallbackAutoConfiguration.class)
                .withBean(SimpleMeterRegistry.class)
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

    @Test
    void repeatedScrapesDoNotRepeatTheCountQuery() {
        // Both gauges answer with a count against the database the engine is bottlenecked on, and
        // the running-process one costs an index entry per running process — the very number it
        // reports. Once per scrape per pod is observability that gets dearer as the system gets
        // busier.
        var processRepository = mock(ProcessRepository.class);
        when(processRepository.countByStatus(ProcessStatus.RUNNING)).thenReturn(7L);
        var outboxRepository = mock(OutboxMessageEntityRepository.class);
        when(outboxRepository.countByStatus("Pending")).thenReturn(3L);

        runner.withBean(SimpleMeterRegistry.class)
                .withBean(ProcessRepository.class, () -> processRepository)
                .withBean(OutboxMessageEntityRepository.class, () -> outboxRepository)
                .run(context -> {
                    var registry = context.getBean(MeterRegistry.class);
                    var running = registry.get(MicrometerWorkflowMetrics.PROCESSES_RUNNING).gauge();
                    var pending = registry.get(MicrometerWorkflowMetrics.OUTBOX_PENDING).gauge();

                    for (var scrape = 0; scrape < 20; scrape++) {
                        assertThat(running.value()).isEqualTo(7);
                        assertThat(pending.value()).isEqualTo(3);
                    }

                    verify(processRepository, times(1)).countByStatus(ProcessStatus.RUNNING);
                    verify(outboxRepository, times(1)).countByStatus("Pending");
                });
    }

    @Test
    void aTtlOfZeroRestoresCountingOnEveryScrape() {
        // The escape hatch for anyone who wants the old behaviour back, and the proof that the
        // caching is the TTL doing its job rather than the value being captured once at startup.
        var processRepository = mock(ProcessRepository.class);
        when(processRepository.countByStatus(ProcessStatus.RUNNING)).thenReturn(7L);

        runner.withBean(SimpleMeterRegistry.class)
                .withBean(ProcessRepository.class, () -> processRepository)
                .withPropertyValues("workflow.metrics.gauge-ttl=PT0S")
                .run(context -> {
                    var gauge = context.getBean(MeterRegistry.class)
                            .get(MicrometerWorkflowMetrics.PROCESSES_RUNNING).gauge();

                    gauge.value();
                    gauge.value();
                    gauge.value();

                    verify(processRepository, times(3)).countByStatus(ProcessStatus.RUNNING);
                });
    }

    @Test
    void theGaugeSurvivesGarbageCollectionOfEverythingButTheRegistry() {
        // Micrometer holds a gauge's source weakly, and the cache it now reads through is
        // referenced by nothing else. Without a strong reference the gauge would start reporting
        // NaN at some unpredictable point after startup.
        var processRepository = mock(ProcessRepository.class);
        when(processRepository.countByStatus(ProcessStatus.RUNNING)).thenReturn(7L);

        runner.withBean(SimpleMeterRegistry.class)
                .withBean(ProcessRepository.class, () -> processRepository)
                .run(context -> {
                    var gauge = context.getBean(MeterRegistry.class)
                            .get(MicrometerWorkflowMetrics.PROCESSES_RUNNING).gauge();
                    System.gc();

                    assertThat(gauge.value()).isEqualTo(7);
                });
    }
}
