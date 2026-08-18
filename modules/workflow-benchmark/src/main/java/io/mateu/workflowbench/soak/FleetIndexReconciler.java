package io.mateu.workflowbench.soak;

import io.mateu.workflowbench.soak.Reconciler.Check;
import io.mateu.workflowbench.soak.Reconciler.Verdict;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fleet database's own verdict: does the standalone projector's read model actually agree with
 * the shards, and did the placement claim keep one business key to one shard?
 *
 * <p>Deliberately <b>additional</b> to {@link Reconciler#verifyAcrossShards}, not a replacement for
 * it. A read model verified by reading the read model proves nothing; the whole value here is that
 * the two sides are reached by different paths — the shards' write tables over their own JDBC
 * connections, the index and the placements over the fleet database — so a projector that quietly
 * fell behind, or a key that was placed twice, shows up as a disagreement rather than as two stores
 * confirming each other.
 *
 * <ul>
 *   <li><b>R8a Index complete</b> — per shard, as many indexed processes as the shard actually holds.
 *       Catches a projector that lagged, died, or was pointed at the wrong topic.</li>
 *   <li><b>R8b Index agrees on status</b> — per shard, the same status histogram on both sides.
 *       Catches ordering bugs: a stale event that clobbered a terminal status shows up here and
 *       nowhere else.</li>
 *   <li><b>R8c One shard per business key</b> — the run's processes across all shards, against the
 *       placement rows for the run. The placement table has the business key as its primary key, so
 *       one process per placement is the invariant; MORE processes than placements means a key was
 *       placed twice and the fleet is running the same work on two shards. That is the exact failure
 *       the claim exists to prevent, so it is worth measuring rather than assuming.</li>
 * </ul>
 */
public final class FleetIndexReconciler {

    private FleetIndexReconciler() {
    }

    public static Verdict verify(JdbcTemplate fleet, Map<String, JdbcTemplate> shardsById, String prefix) {
        var like = prefix + "%";
        var checks = new ArrayList<Check>();

        long presentTotal = 0;
        long indexedTotal = 0;
        long incomplete = 0;
        long statusDisagreements = 0;

        for (var entry : shardsById.entrySet()) {
            var shardId = entry.getKey();
            var shard = entry.getValue();

            var present = count(shard, "SELECT count(*) FROM process_entity WHERE business_key LIKE ?", like);
            var indexed = count(fleet,
                    "SELECT count(*) FROM process_index WHERE business_key LIKE ? AND shard_id = ?",
                    like, shardId);
            presentTotal += present;
            indexedTotal += indexed;
            incomplete += Math.abs(present - indexed);

            statusDisagreements += statusDisagreement(fleet, shard, like, shardId);
        }

        var placements = count(fleet,
                "SELECT count(*) FROM process_placement WHERE business_key LIKE ?", like);

        checks.add(check("R8a", "fleet index complete (per shard, indexed == present)", incomplete));
        checks.add(check("R8b", "fleet index agrees with the shards on status", statusDisagreements));
        // Strictly greater is the failure: fewer processes than placements just means a claimed key
        // whose creation has not landed yet, which is not a duplicate.
        checks.add(check("R8c", "one shard per business key (processes <= placements)",
                Math.max(0, presentTotal - placements)));

        var pass = checks.stream().allMatch(Check::pass);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("present", presentTotal);
        facts.put("indexed", indexedTotal);
        facts.put("placements", placements);
        facts.put("shards", shardsById.size());
        return new Verdict(prefix, pass, checks, facts);
    }

    /** How many processes the two sides disagree about, summed over statuses, for one shard. */
    private static long statusDisagreement(JdbcTemplate fleet, JdbcTemplate shard, String like, String shardId) {
        var write = histogram(shard,
                "SELECT status, count(*) FROM process_entity WHERE business_key LIKE ? GROUP BY status", like);
        var read = histogram(fleet,
                "SELECT status, count(*) FROM process_index WHERE business_key LIKE ? AND shard_id = ? "
                        + "GROUP BY status", like, shardId);
        var statuses = new java.util.LinkedHashSet<String>();
        statuses.addAll(write.keySet());
        statuses.addAll(read.keySet());
        return statuses.stream()
                .mapToLong(s -> Math.abs(write.getOrDefault(s, 0L) - read.getOrDefault(s, 0L)))
                .sum();
    }

    private static Map<String, Long> histogram(JdbcTemplate jdbc, String sql, Object... args) {
        var counts = new LinkedHashMap<String, Long>();
        jdbc.query(sql, rows -> {
            counts.put(rows.getString(1), rows.getLong(2));
        }, args);
        return counts;
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        var value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static Check check(String id, String description, long violations) {
        return new Check(id, description, violations, violations == 0);
    }

    /** Renders the two verdicts as one, so an operator reads a single PASS/FAIL. */
    public static Verdict merge(Verdict shards, Verdict fleet) {
        var checks = new ArrayList<Check>(shards.checks());
        checks.addAll(fleet.checks());
        Map<String, Object> facts = new LinkedHashMap<>(shards.facts());
        facts.putAll(fleet.facts());
        return new Verdict(shards.prefix(),
                checks.stream().allMatch(Check::pass), List.copyOf(checks), facts);
    }
}
