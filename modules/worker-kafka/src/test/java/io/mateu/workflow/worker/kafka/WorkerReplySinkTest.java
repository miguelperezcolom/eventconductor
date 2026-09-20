package io.mateu.workflow.worker.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.worker.api.ReplyNotAcceptedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerReplySinkTest {

    private final RecordingStreamOperations bridge = new RecordingStreamOperations();
    private final WorkerReplySink sink = new WorkerReplySink(bridge);
    private final TaskExecutionRequested task =
            new TaskExecutionRequested("tx-1", "p-1", "wf", "step", "place-order@1", List.of());

    @Test
    void completed_sends_a_completed_status_upstream_with_the_output() {
        sink.completed(task, List.of(new Variable("total", "30")));

        assertThat(bridge.sent).hasSize(1);
        assertThat(bridge.sent.get(0).binding()).isEqualTo("upstream");
        var reply = (TaskStatusChanged) bridge.sent.get(0).payload();
        assertThat(reply.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reply.taskExecutionId()).isEqualTo("tx-1");
        assertThat(reply.processId()).isEqualTo("p-1");
        assertThat(reply.variables()).containsExactly(new Variable("total", "30"));
    }

    @Test
    void running_sends_a_running_status_upstream() {
        sink.running(task);

        var reply = (TaskStatusChanged) bridge.sent.get(0).payload();
        assertThat(reply.status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void failed_sends_the_reason_as_a_log_then_an_error_status() {
        sink.failed(task, List.of(), "OUT_OF_STOCK");

        assertThat(bridge.sent).hasSize(2);
        assertThat(bridge.sent.get(0).payload()).isInstanceOf(TaskLogEmitted.class);
        var log = (TaskLogEmitted) bridge.sent.get(0).payload();
        assertThat(log.message()).isEqualTo("OUT_OF_STOCK");
        var status = (TaskStatusChanged) bridge.sent.get(1).payload();
        assertThat(status.status()).isEqualTo(TaskStatus.ERROR);
    }

    @Test
    void a_broker_that_refuses_the_reply_is_reported_for_redelivery() {
        bridge.accept = false; // WorkerReply exhausts its retries and throws

        assertThatThrownBy(() -> sink.completed(task, List.of()))
                .isInstanceOf(ReplyNotAcceptedException.class);
    }
}
