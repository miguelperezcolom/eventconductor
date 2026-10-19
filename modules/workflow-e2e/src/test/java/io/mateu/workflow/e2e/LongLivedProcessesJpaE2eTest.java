package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPurgeRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessRetentionService;
import io.mateu.workflow.application.services.ProcessTrace;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CONTINUE_AS_NEW and businessKeyPolicy on embedded + JPA (H2): the chain is linked in process_entity, the key
 * held by the latest run in the UNIQUE business_key column — the earlier runs keep theirs in released_business_key
 * (V56) — and the retention purge takes old runs of a chain.
 */
class LongLivedProcessesJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessPurgeRepository purgeRepository;
    @Autowired WorkflowDefinitionRepository definitions;
    @Autowired WorkflowMetrics metrics;
    @Autowired ObjectProvider<ProcessIndexRepository> index;
    @Autowired ObjectProvider<ProcessTrace> trace;
    @Autowired JdbcTemplate jdbc;

    static String value(List<Variable> variables, String name) {
        return variables.stream().filter(v -> name.equals(v.name())).map(Variable::value).findFirst().orElse("0");
    }

    @Test
    void aContinueAsNewChainRunsThroughTheDatabaseAndOldRunsArePurged() {
        worker.on("tick", (request, callback, n) -> callback.complete(List.of(var("count",
                String.valueOf(Integer.parseInt(value(request.variables(), "count")) + 1)))));

        createProcess("can-counter", "jcan-1", new Variable("count", "0"), new Variable("customer", "c-1"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var runs = processRepository.findAllByBusinessKey("jcan-1");
            assertThat(runs).hasSize(3).allMatch(run -> run.getStatus() == ProcessStatus.COMPLETED);
        });
        var runs = processRepository.findAllByBusinessKey("jcan-1");
        assertThat(runs.get(0).getContinuedAsProcessId()).isEqualTo(runs.get(1).getId());
        assertThat(runs.get(1).getContinuedFromProcessId()).isEqualTo(runs.get(0).getId());
        assertThat(runs.get(2).getContinuedFromProcessId()).isEqualTo(runs.get(1).getId());
        assertThat(process("jcan-1").getId()).isEqualTo(runs.get(2).getId());
        assertThat(jdbc.queryForObject("SELECT id FROM process_entity WHERE business_key = 'jcan-1'",
                String.class)).isEqualTo(runs.get(2).getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity WHERE released_business_key = 'jcan-1'",
                Integer.class)).isEqualTo(2);

        // Retention: every run of the chain is a finished top-level process, purged on its own clock.
        var properties = new ProcessRetentionProperties();
        properties.getRetentionByStatus().put("completed", Duration.ofDays(7));
        var service = new ProcessRetentionService(purgeRepository, definitions, metrics, index, trace, properties);
        service.purgeDue(LocalDateTime.now().plusDays(8));
        assertThat(processRepository.findAllByBusinessKey("jcan-1")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity WHERE business_key = 'jcan-1'"
                + " OR released_business_key = 'jcan-1'", Integer.class)).isZero();

        // The key is free again.
        createProcess("can-counter", "jcan-1", new Variable("count", "5"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(processRepository.findAllByBusinessKey("jcan-1")).hasSize(1)
                        .allMatch(run -> run.getStatus() == ProcessStatus.COMPLETED));
    }

    @Test
    void reusePoliciesHandTheKeyOverInTheDatabase() {
        worker.on("bkt-work", TestWorker.deferForever());
        createProcess("bk-terminate", "jbkt-1");
        awaitStatus("jbkt-1", ProcessStatus.RUNNING);
        var first = process("jbkt-1").getId();
        createProcess("bk-terminate", "jbkt-1");
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var runs = processRepository.findAllByBusinessKey("jbkt-1");
            assertThat(runs).extracting(Process::getStatus).containsExactly(ProcessStatus.CANCELLED, ProcessStatus.RUNNING);
        });
        assertThat(process("jbkt-1").getId()).isNotEqualTo(first);

        worker.on("bkf-work", TestWorker.succeed());
        createProcess("bk-after-finished", "jbkf-1");
        awaitStatus("jbkf-1", ProcessStatus.COMPLETED);
        createProcess("bk-after-finished", "jbkf-1");
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(processRepository.findAllByBusinessKey("jbkf-1")).hasSize(2)
                        .allMatch(run -> run.getStatus() == ProcessStatus.COMPLETED));

        worker.on("bku-work", TestWorker.succeed());
        createProcess("bk-unique", "jbku-1");
        awaitStatus("jbku-1", ProcessStatus.COMPLETED);
        createProcess("bk-unique", "jbku-1");
        assertThat(processRepository.findAllByBusinessKey("jbku-1")).hasSize(1);
    }
}
