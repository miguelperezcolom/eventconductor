package io.mateu.workflow.e2e;

import io.mateu.workflow.application.readmodel.ProcessIndexQueryService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Increment 2: the owning shard is stamped on every process's read-model row. Set {@code workflow.sharding.shard-id}
 * and every {@code ProcessStatusChanged} carries it, so the index records where the process lives — the
 * lookup a targeted command (retry/cancel/pause by id) uses to route back to the right shard. Stamped on
 * the event (not read by the projector), so it survives a fanned-out projector consuming across shards.
 */
@TestPropertySource(properties = {"workflow.projection.enabled=true", "workflow.sharding.shard-id=shard-A"})
class ProcessIndexShardIdE2eTest extends AbstractE2eTest {

    @Autowired ProcessIndexQueryService processIndex;

    @Test
    void stampsTheOwningShardOnTheIndexRow() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "shard-row-1");

        var row = processIndex.findByBusinessKey("shard-row-1").orElseThrow();
        assertThat(row.shardId()).isEqualTo("shard-A");
        assertThat(row.status()).isEqualTo(ProcessStatus.COMPLETED.name());
    }
}
