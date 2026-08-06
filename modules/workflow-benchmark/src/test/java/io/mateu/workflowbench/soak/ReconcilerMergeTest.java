package io.mateu.workflowbench.soak;

import io.mateu.workflowbench.soak.Reconciler.Check;
import io.mateu.workflowbench.soak.Reconciler.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fan-out merge: R1 conservation is recomputed globally (Σacked vs Σpresent), so it does not
 * false-fail just because acked lives on one shard while processes are spread across all; the other
 * invariants are summed.
 */
class ReconcilerMergeTest {

    private Verdict shard(long acked, long present, long r1, long r2) {
        return new Verdict("run", r1 == 0 && r2 == 0,
                List.of(new Check("R1", "conservation", r1, r1 == 0),
                        new Check("R2", "terminal", r2, r2 == 0)),
                Map.of("acked", acked, "present", present));
    }

    @Test
    void r1IsGlobalSoAckedOnOneShardAndProcessesOnAllStillReconcile() {
        // Driver wrote acked=100 on shard 0; the 100 processes split 60/40 across two shards. Each
        // shard's own R1 "fails" (acked_i != present_i), but globally 100 acked == 100 present.
        var merged = Reconciler.merge(List.of(shard(100, 60, 40, 0), shard(0, 40, 40, 0)), "run");

        var r1 = merged.checks().stream().filter(c -> c.id().equals("R1")).findFirst().orElseThrow();
        assertThat(r1.violations()).isZero();
        assertThat(r1.pass()).isTrue();
        assertThat(merged.facts()).containsEntry("acked", 100L).containsEntry("present", 100L)
                .containsEntry("shards", 2);
        assertThat(merged.pass()).isTrue();
    }

    @Test
    void otherInvariantsAreSummedAcrossShards() {
        // 2 terminal-status violations on shard 0, 3 on shard 1 → 5 total, and the verdict fails.
        var merged = Reconciler.merge(List.of(shard(50, 30, 0, 2), shard(0, 20, 0, 3)), "run");

        var r2 = merged.checks().stream().filter(c -> c.id().equals("R2")).findFirst().orElseThrow();
        assertThat(r2.violations()).isEqualTo(5);
        assertThat(r2.pass()).isFalse();
        assertThat(merged.pass()).isFalse();
    }
}
