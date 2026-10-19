package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateRejectedException;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessUpdateMcpToolsTest {

    final ProcessUpdateService service = mock(ProcessUpdateService.class);
    final ProcessUpdateMcpTools tools = new ProcessUpdateMcpTools(service);

    static ProcessUpdate update(ProcessUpdate.Status status, String result, String error) {
        return new ProcessUpdate("u-1", "p-1", "orders", "rename", "u-1", null, "{\"pin\":\"****\"}", status, result,
                error, "ana", false, LocalDateTime.of(2026, 10, 19, 9, 0), null, null);
    }

    @Test
    void anUpdateAnswersWithItsOutcome() {
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        when(service.submit(any())).thenReturn(new ProcessUpdateService.Submitted(requested, true, Duration.ofSeconds(1)));
        for (var outcome : List.of(update(ProcessUpdate.Status.COMPLETED, "{\"a\":1}", null),
                update(ProcessUpdate.Status.REFUSED, null, "nope"), update(ProcessUpdate.Status.NOT_ACCEPTED, null, "done"),
                update(ProcessUpdate.Status.FAILED, null, "1/0"), requested)) {
            when(service.await(eq(requested), any())).thenReturn(CompletableFuture.completedFuture(outcome));
            var answer = tools.updateProcess("p-1", null, "rename", "{}", null);
            switch (outcome.status()) {
                case COMPLETED -> assertThat(answer).contains("applied").contains("{\"a\":1}");
                case REFUSED -> assertThat(answer).contains("refused: nope");
                case NOT_ACCEPTED -> assertThat(answer).contains("not accepted: done");
                case FAILED -> assertThat(answer).contains("failed (nothing changed)");
                case REQUESTED -> assertThat(answer).contains("not applied yet");
            }
        }
        org.mockito.Mockito.doThrow(new ProcessUpdateRejectedException(
                ProcessUpdateRejectedException.Reason.NO_SUCH_PROCESS, "No process")).when(service).submit(any());
        assertThat(tools.updateProcess("x", null, "rename", "{}", null)).isEqualTo("Refused: No process");
    }

    @Test
    void updateWithStartSaysWhetherItStartedTheProcess() {
        var done = update(ProcessUpdate.Status.COMPLETED, "1", null);
        when(service.updateWithStart(any())).thenReturn(new ProcessUpdateService.StartSubmitted(done, true, Duration.ZERO));
        when(service.await(eq(done), any())).thenReturn(CompletableFuture.completedFuture(done));

        assertThat(tools.updateWithStart("orders", "k", "rename", "{}", Map.of("a", "b"), null))
                .startsWith("Started process p-1.");

        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad")).when(service).updateWithStart(any());
        assertThat(tools.updateWithStart("orders", "k", "rename", "{}", null, null)).isEqualTo("Refused: bad");
    }

    @Test
    void listsTheUpdatesOfAProcess() {
        when(service.list(eq("p-1"), any())).thenReturn(List.of(update(ProcessUpdate.Status.COMPLETED, "1", null),
                update(ProcessUpdate.Status.REFUSED, null, "why")));
        when(service.list(eq("p-2"), any())).thenReturn(List.of());
        when(service.list(eq("p-3"), any())).thenThrow(new IllegalStateException("gone"));

        assertThat(tools.listProcessUpdates("p-1")).contains("rename [COMPLETED] by ana payload {\"pin\":\"****\"} → 1")
                .contains("[REFUSED]").contains("— why");
        assertThat(tools.listProcessUpdates("p-2")).contains("no updates");
        assertThat(tools.listProcessUpdates("p-3")).isEqualTo("Refused: gone");
        assertThat(tools.getSystemContext()).contains("updateProcess");
    }
}
