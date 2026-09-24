package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.dtos.Variable;
import org.junit.jupiter.api.Test;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/** The reply survives persistence: written with the transition, read back with the process. */
class ReplyStepJpaE2eTest extends AbstractJpaE2eTest {

    @Test
    void theReplyIsPersistedWithTheProcess() {
        worker.on("book", TestWorker.succeed(var("confirmation", "C-9")));

        createProcess("sync-reply-end", "reply-jpa-1", new Variable("bookingId", "B-9"));
        awaitStatus("reply-jpa-1", ProcessStatus.COMPLETED);

        var reply = process("reply-jpa-1").getReply();
        assertThat(reply).isNotNull();
        assertThat(reply.outcome()).isEqualTo(ProcessReply.Outcome.REPLIED);
        assertThat(reply.payload()).isEqualTo("{\"bookingId\":\"B-9\",\"confirmation\":\"C-9\"}");
        assertThat(reply.repliedAt()).isNotNull();
    }
}
