package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The batch operation tools: their published names, and what each asks of the service. */
class BatchOperationMcpToolsTest {

    final BatchOperationService service = mock(BatchOperationService.class);
    final BatchOperationMcpTools tools = new BatchOperationMcpTools(service);

    static BatchOperation batch() {
        return BatchOperation.create("b1", BatchOperationType.RETRY, new BatchOperationFilter("orders", null,
                Set.of(ProcessStatus.ERROR), null, null, null, null, false, "amount > 100"), null, 7, null,
                LocalDateTime.of(2026, 10, 18, 9, 0));
    }

    @Test
    void theToolsArePublished() {
        assertThat(Arrays.stream(BatchOperationMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class)).map(Method::getName).sorted())
                .containsExactly("cancelBatchOperation", "createBatchOperation", "getBatchOperation", "listBatchOperations",
                        "migrateProcessesInBatch");
        assertThat(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()).hasSize(5);
        assertThat(tools.getSystemContext()).contains("createBatchOperation");
    }

    @Test
    void createsWithTheFilterGiven() {
        when(service.create(any(), any(), any(), any())).thenReturn(batch());
        var created = tools.createBatchOperation("retry", "orders", "ERROR, cancelled", null, null, "amount > 100", null, null, null, null);
        var filter = ArgumentCaptor.forClass(BatchOperationFilter.class);
        verify(service).create(eq(BatchOperationType.RETRY), filter.capture(), eq(java.util.Map.of()), isNull());
        assertThat(filter.getValue().status()).containsExactlyInAnyOrder(ProcessStatus.ERROR, ProcessStatus.CANCELLED);
        assertThat(filter.getValue().query()).isEqualTo("amount > 100");
        assertThat(created.id()).isEqualTo("b1");
        assertThat(created.filter()).contains("definition orders");
        assertThat(created.createdAt()).isEqualTo("2026-10-18T09:00");

        assertThatThrownBy(() -> tools.createBatchOperation("explode", "orders", null, null, null, null, null, null, null, null))
                .hasMessageContaining("Unknown batch operation type");
        assertThatThrownBy(() -> tools.createBatchOperation(" ", "orders", null, null, null, null, null, null, null, null))
                .hasMessageContaining("type is required");
        assertThatThrownBy(() -> tools.createBatchOperation("cancel", null, "SLEEPING", null, null, null, null, null, null, null))
                .hasMessageContaining("Unknown status");
    }

    @Test
    void theManualOperationTypesCarryTheirParameters() {
        when(service.create(any(), any(), any(), any())).thenReturn(batch());
        tools.createBatchOperation("set_variables", "orders", null, null, null, null, java.util.Map.of("approved", "yes"),
                "tmp, old", null, "fixing data");
        verify(service).create(eq(BatchOperationType.SET_VARIABLES), any(), eq(java.util.Map.of("set.approved", "yes",
                "remove.tmp", "", "remove.old", "", "reason", "fixing data")), isNull());
        tools.createBatchOperation("RETRY_FROM_STEP", "orders", null, null, null, null, null, null, "charge", "again");
        verify(service).create(eq(BatchOperationType.RETRY_FROM_STEP), any(), eq(java.util.Map.of("stepId", "charge",
                "reason", "again")), isNull());
    }

    @Test
    void readsListsAndCancels() {
        when(service.list()).thenReturn(List.of(batch()));
        when(service.get("b1")).thenReturn(Optional.of(batch()));
        when(service.get("nope")).thenReturn(Optional.empty());
        when(service.cancel("b1")).thenReturn(batch());
        assertThat(tools.listBatchOperations()).extracting(BatchOperationMcpTools.BatchOperationSummary::id).containsExactly("b1");
        assertThat(tools.getBatchOperation("b1").matched()).isEqualTo(7);
        assertThatThrownBy(() -> tools.getBatchOperation("nope")).hasMessageContaining("No batch operation");
        assertThat(tools.cancelBatchOperation("b1").type()).isEqualTo("RETRY");
        assertThat(BatchOperationMcpTools.statusesOf(" , ")).isNull();
    }

    @Test
    void aMigrateBatchIsOverTheDefinitionsLiveProcesses() {
        when(service.create(any(), any(), any(), any())).thenReturn(batch());
        tools.migrateProcessesInBatch("orders", 1, 2, null, java.util.Map.of("b", "b2"), null, "fix");
        var filter = ArgumentCaptor.forClass(BatchOperationFilter.class);
        verify(service).create(eq(BatchOperationType.MIGRATE), filter.capture(),
                eq(java.util.Map.of("targetVersion", "2", "fromVersion", "1", "map.b", "b2", "reason", "fix")), isNull());
        assertThat(filter.getValue().workflowDefinitionId()).isEqualTo("orders");
        assertThat(filter.getValue().status()).containsExactlyInAnyOrder(ProcessStatus.RUNNING, ProcessStatus.PAUSED,
                ProcessStatus.ERROR);

        tools.migrateProcessesInBatch("orders", null, null, "paused", null, java.util.Map.of("x", "1"), "fix");
        verify(service).create(eq(BatchOperationType.MIGRATE), filter.capture(),
                eq(java.util.Map.of("var.x", "1", "reason", "fix")), isNull());
        assertThat(filter.getValue().status()).containsExactly(ProcessStatus.PAUSED);
    }
}
