package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTINUE_AS_NEW chains and every businessKeyPolicy, over memory mode's real engine.
 */
class LongLivedProcessesE2eTest extends AbstractE2eTest {

    static String value(List<Variable> variables, String name, String otherwise) {
        return variables.stream().filter(v -> name.equals(v.name())).map(Variable::value).findFirst().orElse(otherwise);
    }

    @Test
    void continueAsNewChainsRunsUnderOneBusinessKeyCarryingTheMappedVariables() {
        worker.on("tick", (request, callback, invocation) -> {
            var count = Integer.parseInt(value(request.variables(), "count", "0"));
            callback.complete(List.of(var("count", String.valueOf(count + 1)), var("scratch", "x" + invocation)));
        });

        createProcess("can-counter", "can-1", new Variable("count", "0"), new Variable("customer", "c-9"),
                new Variable("bulky", "drop me"));

        var runs = processRepository.findAllByBusinessKey("can-1");
        assertThat(runs).hasSize(3);
        assertThat(runs).allMatch(run -> run.getStatus() == ProcessStatus.COMPLETED);
        var first = runs.get(0);
        var second = runs.get(1);
        var last = runs.get(2);
        assertThat(first.getContinuedFromProcessId()).isNull();
        assertThat(first.getContinuedAsProcessId()).isEqualTo(second.getId());
        assertThat(first.outcome()).isEqualTo("continued");
        assertThat(second.getContinuedFromProcessId()).isEqualTo(first.getId());
        assertThat(second.getContinuedAsProcessId()).isEqualTo(last.getId());
        assertThat(last.getContinuedFromProcessId()).isEqualTo(second.getId());
        assertThat(last.getContinuedAsProcessId()).isNull();
        assertThat(last.outcome()).isNull();
        // Only the mapped variables travel: count and customer, not bulky — and the run's own output.
        assertThat(second.getVariables()).extracting(io.mateu.workflow.domain.aggregates.Variable::name)
                .contains("count", "customer").doesNotContain("bulky");
        assertThat(value(last)).isEqualTo("3");
        // The key resolves to the run that holds it now.
        assertThat(process("can-1").getId()).isEqualTo(last.getId());
        assertThat(steps("can-1")).anyMatch(s -> "done".equals(s.getStepId()));
        assertThat(worker.invocationsOf("tick")).isEqualTo(3);
    }

    private static String value(Process process) {
        return process.getVariables().stream().filter(v -> "count".equals(v.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value).findFirst().orElse(null);
    }

    @Test
    void uniqueCollapsesEveryLaterStartIntoTheFirstRun() {
        worker.on("bku-work", TestWorker.succeed());
        createProcess("bk-unique", "bku-1");
        createProcess("bk-unique", "bku-1");
        assertThat(processRepository.findAllByBusinessKey("bku-1")).hasSize(1);
    }

    @Test
    void allowAfterFinishedReusesTheKeyOnlyOnceTheRunHasEnded() {
        worker.on("bkf-work", TestWorker.deferForever());
        var first = createProcess("bk-after-finished", "bkf-1");
        createProcess("bk-after-finished", "bkf-1");
        assertThat(processRepository.findAllByBusinessKey("bkf-1")).hasSize(1);   // still running: collapsed

        cancelProcessUseCase.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(first.getId()));
        worker.on("bkf-work", TestWorker.succeed());
        createProcess("bk-after-finished", "bkf-1");
        var runs = processRepository.findAllByBusinessKey("bkf-1");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(runs.get(1).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("bkf-1").getId()).isEqualTo(runs.get(1).getId());

        createProcess("bk-after-finished", "bkf-1");   // completed counts as finished too
        assertThat(processRepository.findAllByBusinessKey("bkf-1")).hasSize(3);
    }

    @Test
    void allowAfterFailedReusesTheKeyAfterAFailureButNotAfterASuccess() {
        worker.on("bkx-work", TestWorker.succeed());
        createProcess("bk-after-failed", "bkx-ok");
        createProcess("bk-after-failed", "bkx-ok");
        assertThat(processRepository.findAllByBusinessKey("bkx-ok")).hasSize(1);

        worker.on("bkx-work", TestWorker.failWith("boom"));
        assertThat(createProcess("bk-after-failed", "bkx-ko").getStatus()).isEqualTo(ProcessStatus.ERROR);
        worker.on("bkx-work", TestWorker.succeed());
        createProcess("bk-after-failed", "bkx-ko");
        var runs = processRepository.findAllByBusinessKey("bkx-ko");
        assertThat(runs).extracting(Process::getStatus).containsExactly(ProcessStatus.ERROR, ProcessStatus.COMPLETED);
    }

    @Test
    void terminateRunningCancelsTheRunningHolderAndStartsANewRun() {
        worker.on("bkt-work", TestWorker.deferForever());
        var first = createProcess("bk-terminate", "bkt-1");
        assertThat(first.getStatus()).isEqualTo(ProcessStatus.RUNNING);

        createProcess("bk-terminate", "bkt-1");

        var runs = processRepository.findAllByBusinessKey("bkt-1");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(runs.get(1).getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(process("bkt-1").getId()).isEqualTo(runs.get(1).getId());
    }

    @Test
    void aKeyHeldByAnotherDefinitionIsNeverReused() {
        worker.on("bku-work", TestWorker.succeed());
        createProcess("bk-unique", "shared-key");
        createProcess("bk-after-finished", "shared-key");
        var runs = processRepository.findAllByBusinessKey("shared-key");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getWorkflowDefinitionId()).isEqualTo("bk-unique");
    }
}
