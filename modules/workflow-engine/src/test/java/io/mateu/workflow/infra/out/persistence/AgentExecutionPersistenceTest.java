package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.out.async.OutboxSignal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecutionPersistenceTest {
    @Test void durableMappingRetainsInvocationPayloadReviewAndCheckpoint() {
        var rows = mock(StepExecutionEntityRepository.class);
        var saved = new AtomicReference<StepExecutionEntity>();
        when(rows.save(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return saved.get(); });
        when(rows.findById("step-execution")).thenAnswer(call -> Optional.of(saved.get()));
        var repository = new StepExecutionDBRepository(rows, mock(OutboxMessageEntityRepository.class),
                WorkflowTracing.NOOP, mock(OutboxSignal.class), mock(ObjectProvider.class),
                io.mateu.workflow.application.services.VariableProtection.none());
        var execution = StepExecution.builder().id("step-execution").processId("synthetic-process")
                .stepJson("{\"id\":\"assess\",\"type\":\"AGENT\"}")
                .variables(List.of(new Variable("__agent", "{\"input\":{}}")))
                .invocationId("synthetic-invocation").agentRequest("{\"input\":{}}")
                .agentHoldReason("TIMEOUT").lastAgentStateSequence(20L).failureNonRetryable(true)
                .dispatchCount(7).iteration(2).superseded(true).loopDecision("LOOPED")
                .parentExecutionId("parent").itemIndex(3).scopeJson("{\"item\":\"case\"}").outputJson("{}")
                .status(StepExecutionStatus.REVIEW_REQUIRED).build();

        repository.save(execution);
        var restored = repository.findById("step-execution").orElseThrow();
        assertThat(restored.getInvocationId()).isEqualTo("synthetic-invocation");
        assertThat(restored.getAgentRequest()).isEqualTo(execution.getAgentRequest());
        assertThat(restored.getAgentHoldReason()).isEqualTo("TIMEOUT");
        assertThat(restored.getLastAgentStateSequence()).isEqualTo(20L);
        assertThat(restored.getStatus()).isEqualTo(StepExecutionStatus.REVIEW_REQUIRED);
        assertThat(restored.isFailureNonRetryable()).isTrue();
        assertThat(restored.getDispatchCount()).isEqualTo(7);
        assertThat(restored.getIteration()).isEqualTo(2);
        assertThat(restored.isSuperseded()).isTrue();
        assertThat(restored.getLoopDecision()).isEqualTo("LOOPED");
        assertThat(restored.getParentExecutionId()).isEqualTo("parent");
        assertThat(restored.getItemIndex()).isEqualTo(3);
        assertThat(restored.getScopeJson()).isEqualTo(execution.getScopeJson());
        assertThat(restored.getOutputJson()).isEqualTo("{}");
    }

    @Test void stepsOfSeveralProcessesComeInOneQueryPerChunkGroupedByProcess() {
        var rows = mock(StepExecutionEntityRepository.class);
        var saved = new java.util.ArrayList<StepExecutionEntity>();
        when(rows.save(any())).thenAnswer(call -> { saved.add(call.getArgument(0)); return call.getArgument(0); });
        var repository = new StepExecutionDBRepository(rows, mock(OutboxMessageEntityRepository.class),
                WorkflowTracing.NOOP, mock(OutboxSignal.class), mock(ObjectProvider.class),
                io.mateu.workflow.application.services.VariableProtection.none());
        repository.save(StepExecution.builder().id("a1").processId("a").stepJson("{}").status(StepExecutionStatus.COMPLETED).build());
        repository.save(StepExecution.builder().id("b1").processId("b").stepJson("{}").status(StepExecutionStatus.RUNNING).build());
        repository.save(StepExecution.builder().id("a2").processId("a").stepJson("{}").status(StepExecutionStatus.PENDING).build());
        when(rows.findAllByProcessIdInOrderByOrder(anyCollection())).thenAnswer(call -> {
            java.util.Collection<String> ids = call.getArgument(0);
            return saved.stream().filter(e -> ids.contains(e.getProcessId())).toList();
        });

        var found = repository.findByProcessIds(List.of("a", "b", "none"));

        assertThat(found).containsOnlyKeys("a", "b");
        assertThat(found.get("a")).extracting(StepExecution::id).containsExactly("a1", "a2");
        verify(rows, times(1)).findAllByProcessIdInOrderByOrder(anyCollection());
        verify(rows, never()).findAllByProcessIdOrderByOrder(anyString());
        assertThat(repository.findByProcessIds(List.of())).isEmpty();
    }
}
