package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The JPA counterpart of {@link TerminalLockReleaseE2eTest}: over the JDBC lock tables and the
 * asynchronous relay, a holder that errors or is cancelled frees its key for the next process.
 */
class TerminalLockReleaseJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired CancelProcessUseCase cancelProcessUseCase;

    private static Variable booking(String id) {
        return new Variable("bookingId", id);
    }

    @Test
    void aHolderThatErrorsFreesTheKeyForTheNextProcess() {
        worker.on("work", TestWorker.fail());
        createProcess("lock-serialization", "jlre-A", booking("JLRE-1"));
        awaitStatus("jlre-A", ProcessStatus.ERROR);

        worker.on("work", TestWorker.deferForever());
        createProcess("lock-serialization", "jlre-B", booking("JLRE-1"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("jlre-B", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        assertThat(step("jlre-B", "lock").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void aCancelledHolderAdmitsItsWaiter() {
        worker.on("work", TestWorker.deferForever());
        createProcess("lock-serialization", "jlre-C", booking("JLRE-2"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("jlre-C", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        createProcess("lock-serialization", "jlre-D", booking("JLRE-2"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("jlre-D", "lock").getStatus()).isEqualTo(StepExecutionStatus.WAITING_ON_LOCK));

        cancelProcessUseCase.handle(new CancelProcessCommand(process("jlre-C").getId()));
        awaitStatus("jlre-C", ProcessStatus.CANCELLED);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("jlre-D", "work").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
    }
}
