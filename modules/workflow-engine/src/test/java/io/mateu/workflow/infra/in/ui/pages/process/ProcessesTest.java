package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProcessesTest {

    private final CommandDispatcher commandDispatcher = mock(CommandDispatcher.class);

    private Processes page() {
        return new Processes(null, commandDispatcher, null, null, null, null);
    }

    private ProcessRow row(String id) {
        return new ProcessRow(id, "P", new Status(StatusType.INFO, "Running"), null, null, null);
    }

    /**
     * The point of the bulk button: one click stops every ticked process, each addressed by its own
     * id so the request routes to the pod that owns it.
     */
    @Test
    void cancelRequestsCancellationForEverySelectedProcess() {
        page().cancel(List.of(row("p-1"), row("p-2"), row("p-3")));

        var captor = ArgumentCaptor.forClass(ProcessCancellationRequested.class);
        verify(commandDispatcher, times(3)).dispatch(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ProcessCancellationRequested::processId)
                .containsExactly("p-1", "p-2", "p-3");
        // Addressed by id, never by business key — a process without one would route nowhere.
        assertThat(captor.getAllValues())
                .extracting(ProcessCancellationRequested::businessKey)
                .containsOnlyNulls();
    }

    /** The id is what the event is keyed on, so the owning pod is the one that handles it. */
    @Test
    void cancelKeysEachRequestOnTheProcessId() {
        page().cancel(List.of(row("p-9")));

        var captor = ArgumentCaptor.forClass(ProcessCancellationRequested.class);
        verify(commandDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().partitionKey()).isEqualTo("p-9");
    }

    /** Nothing ticked, nothing dispatched — the button needs a selection anyway. */
    @Test
    void cancelWithNoSelectionDispatchesNothing() {
        page().cancel(List.of());

        verifyNoInteractions(commandDispatcher);
    }

    /** "Cancel all matching" turns the filter on screen — attributes included — into a batch operation. */
    @Test
    void theAllMatchingButtonsCreateABatchFromTheFilterOnScreen() {
        var batches = mock(io.mateu.workflow.application.services.BatchOperationService.class);
        var adapter = new io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter(null, null, null, null, null, null);
        io.mateu.workflow.security.CallerResolver caller = () -> new io.mateu.workflow.security.AuthorizationContext("ana", List.of(), List.of());
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("batches", batches);
        var page = new Processes(adapter, commandDispatcher, null, null, caller,
                beans.getBeanProvider(io.mateu.workflow.application.services.BatchOperationService.class));
        var state = new java.util.HashMap<String, Object>();
        state.put("workflowDefinitionId", "orders");
        state.put("status", "ERROR,CANCELLED");
        state.put("attributes", "customerId = C-9");
        state.put("onlyErrors", true);
        state.put("createdFrom", "2026-10-01");
        var request = mock(io.mateu.uidl.interfaces.HttpRequest.class);
        org.mockito.Mockito.when(request.runActionRq()).thenReturn(io.mateu.dtos.RunActionRqDto.builder()
                .componentState(state).build());
        var created = io.mateu.workflow.domain.batch.BatchOperation.create("b1",
                io.mateu.workflow.domain.batch.BatchOperationType.CANCEL,
                new io.mateu.workflow.domain.batch.BatchOperationFilter("orders", null, null, null, null, null, null, false, null),
                null, 4, null, java.time.LocalDateTime.now());
        org.mockito.Mockito.when(batches.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(created);

        assertThat(page.cancelAllMatching(request).text()).contains("b1", "CANCEL", "4 process(es)");
        var filter = ArgumentCaptor.forClass(io.mateu.workflow.domain.batch.BatchOperationFilter.class);
        verify(batches).create(org.mockito.ArgumentMatchers.eq(io.mateu.workflow.domain.batch.BatchOperationType.CANCEL),
                filter.capture(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        assertThat(filter.getValue().workflowDefinitionId()).isEqualTo("orders");
        assertThat(filter.getValue().status()).containsExactlyInAnyOrder(
                io.mateu.workflow.domain.aggregates.ProcessStatus.ERROR, io.mateu.workflow.domain.aggregates.ProcessStatus.CANCELLED);
        assertThat(filter.getValue().query()).isEqualTo("customerId = C-9");
        assertThat(filter.getValue().onlyErrors()).isTrue();
        assertThat(filter.getValue().createdFrom()).isEqualTo(java.time.LocalDate.of(2026, 10, 1).atStartOfDay());

        org.mockito.Mockito.when(batches.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("A batch operation needs a filter"));
        assertThat(page.retryAllMatching(request).text()).contains("needs a filter");
    }
}
