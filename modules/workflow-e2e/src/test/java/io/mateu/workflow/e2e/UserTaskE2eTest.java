package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-USER-01/02. */
class UserTaskE2eTest extends AbstractE2eTest {

    @Test
    void userTaskCarriesFormIdAndCompletesFlow() {
        worker.on("approve", (req, cb, n) -> {
            assertThat(req.taskId()).isEqualTo("complete-form");
            assertThat(req.variables()).anyMatch(v -> "formId".equals(v.name()) && "approval-form".equals(v.value()));
            cb.complete();
        });

        createProcess("usertask", "ut-1");

        assertThat(process("ut-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void userTaskWithoutFormIdFailsVisibly() {
        // "broken" has no formId → StepExecution.start must fail through the normal
        // pipeline (emitting a status change), so the process ends ERROR, never frozen.
        createProcess("usertask-noform", "ut-2");

        assertThat(step("ut-2", "broken").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("ut-2").getStatus()).isEqualTo(ProcessStatus.ERROR);
    }
}
