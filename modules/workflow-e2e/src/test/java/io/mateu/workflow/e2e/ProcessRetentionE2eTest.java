package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPurgeRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessRetentionService;
import io.mateu.workflow.application.services.ProcessTrace;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention over memory mode's real engine: a parent and the child its PROCESS step started run to
 * the end, and once their retention has passed the purge takes the whole tree — and the business key
 * can start a new process.
 */
class ProcessRetentionE2eTest extends AbstractE2eTest {

    @Autowired ProcessPurgeRepository purgeRepository;
    @Autowired WorkflowDefinitionRepository definitions;
    @Autowired WorkflowMetrics metrics;
    @Autowired ObjectProvider<ProcessIndexRepository> index;
    @Autowired ObjectProvider<ProcessTrace> trace;

    ProcessRetentionService purgeAfter(Duration retention) {
        var properties = new ProcessRetentionProperties();
        properties.setRetention(retention);
        return new ProcessRetentionService(purgeRepository, definitions, metrics, index, trace, properties);
    }

    @Test
    void aFinishedTreeIsPurgedOnceItsRetentionHasPassedAndItsBusinessKeyIsFreeAgain() {
        worker.on("child-work", TestWorker.succeed(var("result", "42")));
        createProcess("parent-flow", "retained-1");
        var spawn = step("retained-1", "spawn-child");
        var childKey = "parent:" + spawn.getId();
        assertThat(process("retained-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process(childKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED);

        // Not yet due: nothing goes.
        var service = purgeAfter(Duration.ofDays(30));
        assertThat(service.purgeDue(LocalDateTime.now()).total()).isZero();
        assertThat(processRepository.findByBusinessKey("retained-1")).isPresent();

        // Due: the parent and its child go together, steps included. The e2e suite shares one store,
        // so finished processes other tests left behind are due too: count at least ours, and check
        // ours by key below.
        var report = service.purgeDue(LocalDateTime.now().plusDays(31));
        assertThat(report.purged().get("parent-flow").get(ProcessStatus.COMPLETED)).isGreaterThanOrEqualTo(1);
        assertThat(report.purged().get("child-flow").get(ProcessStatus.COMPLETED)).isGreaterThanOrEqualTo(1);
        assertThat(processRepository.findByBusinessKey("retained-1")).isEmpty();
        assertThat(processRepository.findByBusinessKey(childKey)).isEmpty();
        assertThat(stepExecutionRepository.findById(spawn.getId())).isEmpty();

        createProcess("parent-flow", "retained-1");
        assertThat(process("retained-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
