package io.mateu.workflow.infra.in.async.processupstreamevent;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * With kafka mode no longer holding a per-process lock, an event that arrives without a partition
 * key is the last way two pods can work the same process. These pin that such an event is sent
 * back out keyed instead of being handled where it landed — and, just as importantly, that
 * nothing else pays for it.
 */
@ExtendWith(MockitoExtension.class)
class UnkeyedEventRouterTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock UpstreamEventPublisher upstreamEventPublisher;

    UnkeyedEventRouter router;

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        router = new UnkeyedEventRouter(stepExecutionRepository);
        ReflectionTestUtils.setField(router, "upstreamEventPublisher", upstreamEventPublisher);
    }

    private void mode(String mode) {
        ReflectionTestUtils.setField(router, "mode", mode);
    }

    private TaskStatusChanged report(String processId) {
        return new TaskStatusChanged("se-1", TaskStatus.COMPLETED, List.of(), processId);
    }

    @Test
    void reroutesAnUnkeyedReportWithTheProcessItBelongsTo() {
        mode("kafka");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(
                StepExecution.builder().id("se-1").processId("p-1").build()));

        assertThat(router.rerouted(report(null))).isTrue();

        var republished = ArgumentCaptor.forClass(TaskStatusChanged.class);
        verify(upstreamEventPublisher).publish(republished.capture());
        assertThat(republished.getValue().processId()).isEqualTo("p-1");
        assertThat(republished.getValue().partitionKey())
                .as("the whole point is that the second time it carries a key").isEqualTo("p-1");
    }

    @Test
    void leavesAKeyedReportAlone() {
        mode("kafka");

        assertThat(router.rerouted(report("p-1"))).isFalse();
        verifyNoInteractions(upstreamEventPublisher, stepExecutionRepository);
    }

    @Test
    void doesNothingOutsideKafkaMode() {
        // No partitions, nothing to route to: the extra hop would buy nothing.
        mode("embedded");

        assertThat(router.rerouted(report(null))).isFalse();
        verifyNoInteractions(upstreamEventPublisher, stepExecutionRepository);
    }

    @Test
    void handlesAReportForAStepThatIsNotThere() {
        mode("kafka");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.empty());

        assertThat(router.rerouted(report(null))).isFalse();
        verifyNoInteractions(upstreamEventPublisher);
    }
}
