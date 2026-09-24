package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.dtos.Variable;
import org.junit.jupiter.api.Test;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a REPLY step records the process's answer on the process, in the step-over that
 * reaches it — at the end (the result) or early (the process carries on afterwards).
 */
class ReplyStepE2eTest extends AbstractE2eTest {

    @Test
    void aReplyBeforeEndAnswersWithTheResult() {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-77")));

        createProcess("sync-reply-end", "reply-end-1", new Variable("bookingId", "B-1"));

        var process = process("reply-end-1");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process.getReply()).isNotNull();
        assertThat(process.getReply().outcome()).isEqualTo(ProcessReply.Outcome.REPLIED);
        assertThat(process.getReply().stepId()).isEqualTo("reply");
        assertThat(process.getReply().payload()).isEqualTo("{\"bookingId\":\"B-1\",\"confirmation\":\"C-77\"}");
        assertThat(step("reply-end-1", "reply").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void anEarlyReplyIsRecordedWhileTheProcessCarriesOn() {
        worker.on("notify", TestWorker.deferForever());

        createProcess("sync-reply-early", "reply-early-1", new Variable("bookingId", "B-2"));

        var process = process("reply-early-1");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(process.getReply().payload()).contains("\"bookingId\":\"B-2\"").contains("\"status\":\"ACCEPTED\"");
        assertThat(step("reply-early-1", "notify").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void aReplyTemplateAnswersWithStructuredTypedJson() {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-T1")));

        createProcess("sync-reply-template", "reply-template-1", new Variable("bookingId", "B-T1"),
                new Variable("nights", "3"));

        var payload = process("reply-template-1").getReply().payload();
        assertThat(payload).contains("\"booking\":{\"id\":\"B-T1\",\"confirmation\":\"C-T1\"}")
                .contains("\"nights\":3").contains("\"summary\":\"Booking B-T1 confirmed as C-T1\"");
    }
}
