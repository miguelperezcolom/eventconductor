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
        return new Processes(null, commandDispatcher, null);
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
}
