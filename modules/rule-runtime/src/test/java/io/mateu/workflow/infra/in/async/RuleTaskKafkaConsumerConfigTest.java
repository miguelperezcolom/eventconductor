package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.FactCoercer;
import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.WorkerReply;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules worker's job here is not evaluating rules — that is covered by the evaluator's own
 * tests. It is answering the engine: a RULE step waits for this reply and, until it arrives, the
 * process does not move. So what matters is the reply going out with the right shape, and a reply
 * the broker will not take escaping loudly rather than being logged and forgotten.
 */
class RuleTaskKafkaConsumerConfigTest {

    private static final TaskExecutionRequested TASK = new TaskExecutionRequested(
            "task-1", "process-1", "wf-1", "step-1", RuleTaskKafkaConsumerConfig.EVALUATE_RULE_TASK_ID,
            List.of(new Variable("ruleId", "discount"), new Variable("amount", "150")));

    /** A rule that matches when amount > 100 and answers with a discount. */
    private static RuleEvaluator evaluator() {
        var rule = new Rule("discount", "discount", null, RuleType.EXPRESSION, 1, 0, List.of(),
                "amount > 100", List.of(new Assignment("discount", "amount * 0.1")),
                null, null, null, null);
        return new RuleEvaluator(new RuleSource() {
            @Override
            public Optional<Rule> findById(String id) {
                return "discount".equals(id) ? Optional.of(rule) : Optional.empty();
            }

            @Override
            public List<Rule> findAll() {
                return List.of(rule);
            }
        });
    }

    private static java.util.function.Consumer<io.mateu.workflow.ddd.DomainEvent> workerWith(
            StreamOperations bridge) {
        return new RuleTaskKafkaConsumerConfig(evaluator(), new FactCoercer(), bridge)
                .consumeWorkerEventForRuleRuntime();
    }

    @Test
    void repliesCompletedWithTheRuleOutputs() {
        var bridge = new RecordingBridge(true);

        workerWith(bridge).accept(TASK);

        var reply = bridge.replies().getFirst();
        assertThat(reply.taskExecutionId()).isEqualTo("task-1");
        assertThat(reply.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reply.variables()).extracting(Variable::name).contains("discount");
    }

    @Test
    void echoesTheProcessSoTheReplyReachesItsOwner() {
        // Without the key the reply lands on whichever partition the partitioner picks, and the
        // pod that owns the process never sees it.
        var bridge = new RecordingBridge(true);

        workerWith(bridge).accept(TASK);

        assertThat(bridge.replies().getFirst().partitionKey()).isEqualTo("process-1");
    }

    @Test
    void reportsAnUnevaluableRuleAsAnErrorStatusRatherThanSilence() {
        var bridge = new RecordingBridge(true);
        var noRuleId = new TaskExecutionRequested("task-2", "process-2", "wf-1", "step-1",
                RuleTaskKafkaConsumerConfig.EVALUATE_RULE_TASK_ID, List.of());

        workerWith(bridge).accept(noRuleId);

        assertThat(bridge.replies().getFirst().status()).isEqualTo(TaskStatus.ERROR);
    }

    @Test
    void throwsWhenTheBrokerWillNotTakeTheReply() {
        // The point of the whole exercise: the consumer must fail so the offset is not committed
        // and Kafka hands the task back. A quiet return here is a step that waits forever.
        var bridge = new RecordingBridge(false);

        assertThatThrownBy(() -> workerWith(bridge).accept(TASK))
                .isInstanceOf(WorkerReply.ReplyNotAcceptedException.class);
    }

    @Test
    void throwsWhenTheRuleFailedAndTheErrorReplyCannotBePublishedEither() {
        // The evaluation failure is a business outcome and is reported, not retried — but a
        // failure to publish that report is still a lost reply, and must escape the catch.
        var bridge = new RecordingBridge(false);
        var noRuleId = new TaskExecutionRequested("task-2", "process-2", "wf-1", "step-1",
                RuleTaskKafkaConsumerConfig.EVALUATE_RULE_TASK_ID, List.of());

        assertThatThrownBy(() -> workerWith(bridge).accept(noRuleId))
                .isInstanceOf(WorkerReply.ReplyNotAcceptedException.class);
    }

    @Test
    void aLostLogLineDoesNotStopTheReply() {
        // The log line annotates the timeline; the reply moves the process. Losing the first must
        // not cost the second.
        var bridge = new RecordingBridge(true) {
            @Override
            public boolean send(String binding, Object payload) {
                if (payload instanceof TaskLogEmitted) {
                    throw new IllegalStateException("broker refused the log line");
                }
                return super.send(binding, payload);
            }
        };

        workerWith(bridge).accept(TASK);

        assertThat(bridge.replies()).hasSize(1);
        assertThat(bridge.replies().getFirst().status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void ignoresTasksThatAreNotRuleEvaluations() {
        var bridge = new RecordingBridge(true);

        workerWith(bridge).accept(new TaskExecutionRequested(
                "task-3", "process-3", "wf-1", "step-1", "send-email", List.of()));

        assertThat(bridge.payloads).isEmpty();
    }

    /** Answers each send from a scripted list, repeating the last answer once it runs out. */
    private static class RecordingBridge implements StreamOperations {

        private final boolean[] answers;
        final List<Object> payloads = new ArrayList<>();

        RecordingBridge(boolean... answers) {
            this.answers = answers;
        }

        List<TaskStatusChanged> replies() {
            return payloads.stream()
                    .filter(TaskStatusChanged.class::isInstance)
                    .map(TaskStatusChanged.class::cast)
                    .toList();
        }

        @Override
        public boolean send(String binding, Object payload) {
            payloads.add(payload);
            return answers[Math.min(payloads.size() - 1, answers.length - 1)];
        }

        @Override
        public boolean send(String binding, Object payload, MimeType mimeType) {
            return send(binding, payload);
        }

        @Override
        public boolean send(String binding, String contentType, Object payload) {
            return send(binding, payload);
        }

        @Override
        public boolean send(String binding, String contentType, Object payload, MimeType mimeType) {
            return send(binding, payload);
        }
    }
}
