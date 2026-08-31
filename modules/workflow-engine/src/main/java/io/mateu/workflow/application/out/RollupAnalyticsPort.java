package io.mateu.workflow.application.out;

import java.time.LocalDateTime;

/**
 * The analytics read model, seen from the store that delegates to it. When it is present — the
 * feature is opt-in — the JPA repositories answer {@code aggregateProcesses}/{@code aggregateSteps}
 * from it instead of from a {@code GROUP BY} over the raw tables, and the numbers are the same
 * (bar an approximate p95). When it is absent, nothing changes.
 *
 * <p>Deliberately the same two methods and the same return types the ports already expose: the read
 * model is a faster way to the identical answer, not a different answer, so it plugs in behind the
 * existing seam rather than beside it.
 */
public interface RollupAnalyticsPort {

    AnalyticsAggregates.ProcessAggregates aggregateProcesses(LocalDateTime from, LocalDateTime to);

    AnalyticsAggregates.StepAggregates aggregateSteps(LocalDateTime from, LocalDateTime to);
}
