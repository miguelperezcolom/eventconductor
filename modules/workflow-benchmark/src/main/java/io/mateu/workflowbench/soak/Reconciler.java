package io.mateu.workflowbench.soak;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The zero-loss verdict, computed from the database alone after a run has drained.
 *
 * <p>It reconciles what the driver handed over (the {@code soak_progress} acked count, written with
 * {@code acks=all} + idempotence so a survived outage cannot inflate it) against what the engine
 * shows, and asserts every invariant a workflow engine must never break. It is deliberately
 * independent of the harness: a driver, a pod or a node can die at any point and the verdict still
 * reads the truth off the tables.
 *
 * <p>Everything is scoped to one run by its business-key prefix — including the child processes the
 * {@code child} definition spawns, whose own business key is {@code parent:<…>} but which are
 * reachable through their parent's step execution. The invariants:
 *
 * <ul>
 *   <li><b>R1 Conservation</b> — acked creations == processes present.</li>
 *   <li><b>R2 Terminal</b> — every run process (and child) reached a terminal status.</li>
 *   <li><b>R3 No stuck steps</b> — no live step after drain, and none of the silent kind: a
 *       deadline-less live ACTION/RULE step (the exact 3,356-stuck trap — the gauge alone is not
 *       trusted).</li>
 *   <li><b>R4 Exactly-once</b> — no {@code (process, step)} ran twice.</li>
 *   <li><b>R5 Outbox</b> — everything relayed ({@code status='Sent'}), nothing poisoned
 *       ({@code 'Error'}).</li>
 *   <li><b>R7 Saga outcomes</b> — every process ended in the terminal status its injected intent
 *       (encoded in the business key) demanded: {@code ok→COMPLETED}, {@code comp→COMPENSATED},
 *       {@code compfail→COMPENSATION_FAILED}.</li>
 * </ul>
 *
 * <p>(R6 — outbox {@code Sent} count vs. Kafka topic end-offsets — needs the broker admin API and is
 * checked by the controller, not here.)
 *
 * <p>At 20M the scoped counts run per business-key range against the deadline/status indexes; this
 * single-pass form is the same queries without the ranging, correct at pilot scale.
 */
public final class Reconciler {

    private static final String PROCESS_TERMINAL =
            "('COMPLETED','CANCELLED','ERROR','COMPENSATED','COMPENSATION_FAILED')";
    private static final String STEP_TERMINAL =
            "('COMPLETED','CANCELLED','ERROR','TIMEOUT')";

    /** The run's processes plus the child processes they spawned, as a CTE reused by the checks. */
    private static final String RUN_CTE = """
            WITH run AS (
              SELECT id FROM process_entity WHERE business_key LIKE ?
              UNION
              SELECT c.id FROM process_entity c
                JOIN step_execution_entity se ON se.id = c.parent_step_execution_id
                JOIN process_entity p ON p.id = se.process_id
               WHERE p.business_key LIKE ?
            )
            """;

    public record Check(String id, String description, long violations, boolean pass) {}

    public record Verdict(String prefix, boolean pass, List<Check> checks, Map<String, Object> facts) {
        public String render() {
            var sb = new StringBuilder();
            sb.append(pass ? "PASS" : "FAIL").append(" — reliability verdict for prefix '")
                    .append(prefix).append("'\n");
            facts.forEach((k, v) -> sb.append("  ").append(k).append('=').append(v).append('\n'));
            for (var c : checks) {
                sb.append(c.pass() ? "  [ok]   " : "  [FAIL] ")
                        .append(c.id()).append(" — ").append(c.description());
                if (!c.pass()) {
                    sb.append(" (").append(c.violations()).append(" violations)");
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    public static Verdict verify(JdbcTemplate jdbc, String prefix) {
        var like = prefix + "-%";
        var checks = new ArrayList<Check>();

        long acked = firstLong(jdbc,
                "SELECT coalesce(max(acked),0) FROM soak_progress WHERE prefix = ?", prefix);
        long present = count(jdbc,
                "SELECT count(*) FROM process_entity WHERE business_key LIKE ?", like);
        checks.add(check("R1", "conservation: acked == processes present",
                Math.abs(acked - present)));

        checks.add(check("R2", "every run process (and child) reached a terminal status",
                count(jdbc, RUN_CTE + "SELECT count(*) FROM process_entity p JOIN run ON run.id = p.id "
                        + "WHERE p.status NOT IN " + PROCESS_TERMINAL, like, like)));

        checks.add(check("R3a", "no live step after drain",
                count(jdbc, RUN_CTE + "SELECT count(*) FROM step_execution_entity se "
                        + "JOIN run ON run.id = se.process_id WHERE se.status NOT IN " + STEP_TERMINAL,
                        like, like)));

        checks.add(check("R3b", "no deadline-less live ACTION/RULE step (silent-stall trap)",
                count(jdbc, RUN_CTE + "SELECT count(*) FROM step_execution_entity se "
                        + "JOIN run ON run.id = se.process_id "
                        + "WHERE se.deadline_at IS NULL AND se.status IN ('PENDING','RUNNING') "
                        + "AND se.step_type IN ('ACTION','RULE')", like, like)));

        checks.add(check("R4", "exactly-once: no (process, step) executed twice",
                count(jdbc, RUN_CTE + "SELECT count(*) FROM (SELECT se.process_id, se.step_id "
                        + "FROM step_execution_entity se JOIN run ON run.id = se.process_id "
                        + "GROUP BY se.process_id, se.step_id HAVING count(*) > 1) x", like, like)));

        // Outbox is not business-key scoped; after a drained run the whole outbox must be Sent.
        checks.add(check("R5a", "outbox fully relayed (nothing left un-Sent)",
                count(jdbc, "SELECT count(*) FROM outbox_message_entity WHERE status <> 'Sent'")));
        checks.add(check("R5b", "no poisoned outbox rows (status='Error')",
                count(jdbc, "SELECT count(*) FROM outbox_message_entity WHERE status = 'Error'")));

        checks.add(sagaOutcomes(jdbc, prefix));

        var pass = checks.stream().allMatch(Check::pass);
        var facts = Map.<String, Object>of("acked", acked, "present", present);
        return new Verdict(prefix, pass, checks, facts);
    }

    /**
     * R7 — every order-saga process ended in the terminal status its intent demanded. The intent is
     * the 4th business-key segment ({@code <prefix>-order-saga-<intent>-<i>}); a row whose status
     * does not match the intent's expected terminal is a violation.
     */
    private static Check sagaOutcomes(JdbcTemplate jdbc, String prefix) {
        var sql = """
                SELECT count(*) FROM process_entity
                WHERE business_key LIKE ?
                  AND NOT (
                       (business_key LIKE ? AND status = 'COMPLETED')
                    OR (business_key LIKE ? AND status = 'COMPENSATED')
                    OR (business_key LIKE ? AND status = 'COMPENSATION_FAILED')
                  )
                """;
        long violations = count(jdbc, sql,
                prefix + "-order-saga-%",
                prefix + "-order-saga-ok-%",
                prefix + "-order-saga-comp-%",
                prefix + "-order-saga-compfail-%");
        return check("R7", "saga outcomes match injected intent (ok/comp/compfail)", violations);
    }

    private static Check check(String id, String description, long violations) {
        return new Check(id, description, violations, violations == 0);
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static long firstLong(JdbcTemplate jdbc, String sql, Object... args) {
        return count(jdbc, sql, args);
    }

    private Reconciler() {
    }
}
