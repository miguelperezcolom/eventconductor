package io.mateu.workflow.worker;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What this class has to get right is the unhappy path: a refused reply must not be dropped, and
 * a reply that cannot be published at all must fail loudly enough that the offset is not
 * committed. The happy path is one line and is the least interesting thing here.
 */
class WorkerReplyTest {

    private static final TaskExecutionRequested TASK = new TaskExecutionRequested(
            "task-1", "process-1", "wf", "s1", "", List.of());

    @Test
    void publishesTheReplyOnTheUpstreamBinding() {
        var bridge = new RecordingBridge(true);

        WorkerReply.completed(bridge, TASK, List.of(new Variable("total", "7")));

        assertThat(bridge.bindings).containsExactly("upstream");
        var reply = (TaskStatusChanged) bridge.payloads.getFirst();
        assertThat(reply.taskExecutionId()).isEqualTo("task-1");
        assertThat(reply.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reply.variables()).extracting(Variable::name).containsExactly("total");
    }

    @Test
    void echoesTheProcessSoTheReplyIsRoutedToItsOwner() {
        // The partition key is the process. Without it the reply lands wherever the partitioner
        // puts it and the pod that owns the process never sees it.
        var bridge = new RecordingBridge(true);

        WorkerReply.running(bridge, TASK);

        assertThat(((TaskStatusChanged) bridge.payloads.getFirst()).partitionKey())
                .isEqualTo("process-1");
    }

    @Test
    void reportsFailureWithTheErrorStatus() {
        var bridge = new RecordingBridge(true);

        WorkerReply.failed(bridge, TASK, List.of());

        assertThat(((TaskStatusChanged) bridge.payloads.getFirst()).status())
                .isEqualTo(TaskStatus.ERROR);
    }

    @Test
    void retriesARefusedSendUntilItIsAccepted() {
        var bridge = new RecordingBridge(false, false, true);

        WorkerReply.completed(bridge, TASK, List.of());

        assertThat(bridge.payloads).hasSize(3);
    }

    @Test
    void throwsWhenTheBrokerNeverAcceptsIt() {
        // The whole point: the listener must fail so the offset is not committed and Kafka
        // redelivers the task. Returning quietly here is what left 3 356 processes stuck.
        var bridge = new RecordingBridge(false);

        assertThatThrownBy(() -> WorkerReply.completed(bridge, TASK, List.of()))
                .isInstanceOf(WorkerReply.ReplyNotAcceptedException.class)
                .hasMessageContaining("task-1")
                .hasMessageContaining("redelivered");
        assertThat(bridge.payloads).hasSizeGreaterThan(1);
    }

    @Test
    void treatsAThrownSendAsARefusal() {
        // A synchronous binding surfaces a broker failure as an exception just as readily as a
        // false return; swallowing one and not the other would leave half the hole open.
        var bridge = new StreamOperations() {
            int attempts;

            @Override
            public boolean send(String binding, Object payload) {
                attempts++;
                throw new IllegalStateException("broker down");
            }

            @Override
            public boolean send(String b, Object p, MimeType m) {
                return send(b, p);
            }

            @Override
            public boolean send(String b, String c, Object p) {
                return send(b, p);
            }

            @Override
            public boolean send(String b, String c, Object p, MimeType m) {
                return send(b, p);
            }
        };

        assertThatThrownBy(() -> WorkerReply.completed(bridge, TASK, List.of()))
                .isInstanceOf(WorkerReply.ReplyNotAcceptedException.class)
                .hasRootCauseMessage("broker down");
        assertThat(bridge.attempts).isGreaterThan(1);
    }

    /** Answers each send from a scripted list, repeating the last answer once it runs out. */
    private static final class RecordingBridge implements StreamOperations {

        private final boolean[] answers;
        final List<String> bindings = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        RecordingBridge(boolean... answers) {
            this.answers = answers;
        }

        @Override
        public boolean send(String binding, Object payload) {
            bindings.add(binding);
            payloads.add(payload);
            return answers[Math.min(payloads.size() - 1, answers.length - 1)];
        }

        @Override
        public boolean send(String binding, Object payload, MimeType mimeType) {
            return send(binding, payload);
        }

        @Override
        public boolean send(String binding, String channel, Object payload) {
            return send(binding, payload);
        }

        @Override
        public boolean send(String binding, String channel, Object payload, MimeType mimeType) {
            return send(binding, payload);
        }
    }
}
