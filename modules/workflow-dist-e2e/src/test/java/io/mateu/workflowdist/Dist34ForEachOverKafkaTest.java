package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-34 — forEach and repeat in Kafka mode: every iteration is its own task over the downstream
 * topic, its reply comes back over upstream keyed by the process, and the container collects the
 * output in item order through the outbox; a repeat's passes are new step executions.
 */
class Dist34ForEachOverKafkaTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
    }

    @Test
    void aForEachRunsOneTaskPerItemAndCollectsTheOutputInOrder() {
        WorkerStub.on("dist-foreach", "each", (request, invocation) -> {
            var item = request.variables().stream().filter(v -> "item".equals(v.name()))
                    .map(Variable::value).findFirst().orElseThrow();
            WorkerStub.complete(request, new Variable("doubled", String.valueOf(Integer.parseInt(item) * 2)));
        });

        createProcess("dist-foreach", "dist34-1", new Variable("items", "[1,2,3,4,5]"));

        awaitProcessCompleted("dist34-1");
        var processId = processId("dist34-1");
        assertThat(WorkerStub.executionCount(processId, "each")).isEqualTo(5);
        assertThat(WorkerStub.receivedFor(processId).stream().filter(r -> "each".equals(r.stepId()))
                .map(r -> r.taskExecutionId()).distinct()).hasSize(5);
        assertThat(WorkerStub.executionCount(processId, "poll")).isEqualTo(2);
        var variables = DistInfra.jdbc().queryForObject(
                "SELECT variables FROM process_entity WHERE business_key = 'dist34-1'", String.class);
        assertThat(variables).contains("[\\\"2\\\",\\\"4\\\",\\\"6\\\",\\\"8\\\",\\\"10\\\"]");
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM step_execution_entity s JOIN process_entity p ON p.id = s.process_id"
                        + " WHERE p.business_key = 'dist34-1' AND s.parent_execution_id IS NOT NULL", Integer.class))
                .isEqualTo(5);
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM step_execution_entity s JOIN process_entity p ON p.id = s.process_id"
                        + " WHERE p.business_key = 'dist34-1' AND s.step_id = 'poll' AND s.superseded = true", Integer.class))
                .isEqualTo(1);
    }
}
