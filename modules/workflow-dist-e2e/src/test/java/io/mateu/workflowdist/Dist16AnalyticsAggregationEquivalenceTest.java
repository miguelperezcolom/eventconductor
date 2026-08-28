package io.mateu.workflowdist;

import io.mateu.workflow.application.out.AnalyticsAggregates;
import io.mateu.workflow.application.out.ProcessAnalyticsRow;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionAnalyticsRow;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.out.persistence.ProcessEntity;
import io.mateu.workflow.infra.out.persistence.ProcessEntityRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntity;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-16 — The analytics aggregates computed in SQL are the ones that were computed in Java.
 *
 * <p>Analytics used to fold every process and every step execution in the window on the request
 * thread. That reduction now happens in the database, and this is the test that makes the swap
 * safe: the same rows, aggregated both ways, asserted equal number by number — counts by status,
 * counts per day, sample counts, totals, and the nearest-rank 95th percentile.
 *
 * <p><b>Why here and not beside the repository.</b> The Java side runs anywhere; the SQL side is
 * the part that can be wrong, and it can only be wrong against a real database. Two analytics bugs
 * reached a release through exactly that gap — {@code workflow-engine}'s tests run on H2 and the
 * service test mocks the repositories, so 2.6.0 shipped a query PostgreSQL refused to prepare.
 *
 * <p>The Java side is not a copy written for the test: it is the port's own default implementation,
 * the one memory mode uses, reached through an adapter that supplies the rows and inherits
 * everything else. So what is compared is the two implementations that ship.
 *
 * <p>The dataset is deliberately awkward — durations that differ by a millisecond so a percentile
 * cannot be right by rounding, a process with no {@code started} so the fallback to {@code created}
 * is exercised, a step that never finished so it counts without contributing a duration, two
 * definitions so nothing is right by having only one group, and 41 processes so
 * {@code ceil(0.95 × n)} lands strictly inside the sorted values rather than on the last one.
 *
 * <p><b>Verified to discriminate</b>, twice. With the SQL percentile changed to
 * {@code percentile_cont}, which interpolates: 1 038 300 000 ns against Java's 1 039 000 000 —
 * three tenths of the way between two samples, a duration nothing ever took. With it changed to
 * {@code percentile_disc(0.5)}: 1 020 000 000, the median. Both fail on the two percentile
 * assertions and on nothing else — the counts and totals still agree, which is what tells you the
 * sabotage was the percentile and not the plumbing.
 *
 * <p>An earlier version of this seed varied durations by a nanosecond and could not fail at all:
 * PostgreSQL keeps timestamps to the microsecond, so every duration arrived as exactly one second,
 * and a distribution of one value has no percentile to get wrong. That is the reason the seed
 * varies by milliseconds, and the reason this paragraph exists.
 */
class Dist16AnalyticsAggregationEquivalenceTest extends AbstractDistTest {

    static final int PROCESSES_PER_DEFINITION = 41;

    static ConfigurableApplicationContext orchestrator;
    static ProcessEntityRepository processEntities;
    static StepExecutionEntityRepository stepEntities;
    static ProcessRepository processes;
    static StepExecutionRepository steps;

    @BeforeAll
    static void startPodAndSeed() {
        orchestrator = DistInfra.startOrchestrator(Map.of());
        processEntities = orchestrator.getBean(ProcessEntityRepository.class);
        stepEntities = orchestrator.getBean(StepExecutionEntityRepository.class);
        processes = orchestrator.getBean(ProcessRepository.class);
        steps = orchestrator.getBean(StepExecutionRepository.class);
        stepEntities.deleteAll();
        processEntities.deleteAll();
        seed();
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void everyProcessNumberIsTheSameBothWays() {
        var sql = processes.aggregateProcesses(null, null);
        var java = javaSideProcesses().aggregateProcesses(null, null);

        assertThat(sorted(sql.statusCounts())).isEqualTo(sorted(java.statusCounts()));
        assertThat(sortedDays(sql.createdPerDay())).isEqualTo(sortedDays(java.createdPerDay()));
        assertThat(sortedDays(sql.finishedPerDay())).isEqualTo(sortedDays(java.finishedPerDay()));
        assertThat(sortedDurations(sql.durations())).isEqualTo(sortedDurations(java.durations()));
    }

    @Test
    void everyStepNumberIsTheSameBothWays() {
        var sql = steps.aggregateSteps(null, null);
        var java = javaSideSteps().aggregateSteps(null, null);

        assertThat(sortedStepCounts(sql.counts())).isEqualTo(sortedStepCounts(java.counts()));
        assertThat(sortedStepDurations(sql.durations())).isEqualTo(sortedStepDurations(java.durations()));
    }

    /**
     * The percentile in particular, called out on its own: it is the only aggregate that is not a
     * count or a sum, and the one where an implementation can be plausible and wrong.
     */
    @Test
    void theNinetyFifthPercentileIsAMeasuredSampleAndNotAnInterpolation() {
        var sql = processes.aggregateProcesses(null, null).durations();
        var java = javaSideProcesses().aggregateProcesses(null, null).durations();

        assertThat(sql).isNotEmpty();
        for (var aggregate : sql) {
            var counterpart = java.stream()
                    .filter(d -> d.workflowDefinitionId().equals(aggregate.workflowDefinitionId()))
                    .findFirst().orElseThrow();
            assertThat(aggregate.duration().p95Nanos())
                    .as("p95 of %s", aggregate.workflowDefinitionId())
                    .isEqualTo(counterpart.duration().p95Nanos());
            // Nearest rank means the value is one of the samples. An interpolating percentile
            // would land between two of them and satisfy neither this nor the equality above.
            assertThat(durationsOf(aggregate.workflowDefinitionId()))
                    .as("p95 of %s must be a duration that was actually measured",
                            aggregate.workflowDefinitionId())
                    .contains(aggregate.duration().p95Nanos());
        }
    }

    // ── the Java side: the port's own defaults, given the rows and nothing else ──────────────

    private static ProcessRepository javaSideProcesses() {
        return new ProcessRepository() {
            @Override
            public List<ProcessAnalyticsRow> findAnalyticsRows(LocalDateTime from, LocalDateTime to) {
                return processes.findAnalyticsRows(from, to);
            }

            @Override public Optional<Process> findById(String id) { throw new UnsupportedOperationException(); }
            @Override public String save(Process process) { throw new UnsupportedOperationException(); }
            @Override public List<Process> findAll() { throw new UnsupportedOperationException(); }
            @Override public void deleteAllById(List<String> ids) { throw new UnsupportedOperationException(); }
            @Override public Optional<Process> findByBusinessKey(String key) { throw new UnsupportedOperationException(); }
        };
    }

    private static StepExecutionRepository javaSideSteps() {
        return new StepExecutionRepository() {
            @Override
            public List<StepExecutionAnalyticsRow> findAnalyticsRows(LocalDateTime from, LocalDateTime to) {
                return steps.findAnalyticsRows(from, to);
            }

            @Override public Optional<StepExecution> findById(String id) { throw new UnsupportedOperationException(); }
            @Override public String save(StepExecution stepExecution) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findAll() { throw new UnsupportedOperationException(); }
            @Override public void deleteAllById(List<String> ids) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findByProcessId(String processId) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findPendingOrRunning() { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findPendingOrRunningByProcessId(String id) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findDue(LocalDateTime now) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findDueByProcessId(String id, LocalDateTime now) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findDueRetriesByProcessId(String id, LocalDateTime now) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findWaitingForMessage(String name, String key) { throw new UnsupportedOperationException(); }
        };
    }

    // ── comparison helpers: order is not part of the contract, the numbers are ───────────────

    private static List<String> sorted(List<AnalyticsAggregates.DefinitionStatusCount> counts) {
        return counts.stream()
                .map(c -> c.workflowDefinitionId() + "|" + c.status() + "|" + c.count() + "|" + c.anyProcessName())
                .sorted().toList();
    }

    private static List<String> sortedDays(List<AnalyticsAggregates.DefinitionDayCount> counts) {
        return counts.stream()
                .map(c -> c.workflowDefinitionId() + "|" + c.day() + "|" + c.count())
                .sorted().toList();
    }

    private static List<String> sortedDurations(List<AnalyticsAggregates.DefinitionDuration> durations) {
        return durations.stream()
                .map(d -> d.workflowDefinitionId() + "|" + d.duration().samples()
                        + "|" + d.duration().totalNanos() + "|" + d.duration().p95Nanos())
                .sorted().toList();
    }

    private static List<String> sortedStepCounts(List<AnalyticsAggregates.DefinitionStepCount> counts) {
        return counts.stream()
                .map(c -> c.workflowDefinitionId() + "|" + c.stepId() + "|" + c.status()
                        + "|" + c.count() + "|" + c.firstOrder())
                .sorted().toList();
    }

    private static List<String> sortedStepDurations(List<AnalyticsAggregates.DefinitionStepDuration> durations) {
        return durations.stream()
                .map(d -> d.workflowDefinitionId() + "|" + d.stepId() + "|" + d.duration().samples()
                        + "|" + d.duration().totalNanos() + "|" + d.duration().p95Nanos())
                .sorted().toList();
    }

    private static List<Long> durationsOf(String definitionId) {
        return processes.findAnalyticsRows(null, null).stream()
                .filter(row -> definitionId.equals(row.workflowDefinitionId()))
                .map(ProcessRepository::durationNanosOf)
                .filter(nanos -> nanos != null)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    // ── the dataset ─────────────────────────────────────────────────────────────────────────

    private static void seed() {
        var base = LocalDateTime.of(2026, 8, 1, 9, 0);
        var processRows = new ArrayList<ProcessEntity>();
        var stepRows = new ArrayList<StepExecutionEntity>();

        for (var definition : List.of("def-a", "def-b")) {
            for (var i = 0; i < PROCESSES_PER_DEFINITION; i++) {
                var id = definition + "-p" + i;
                // Two days, so the per-day counts have more than one bucket to get right.
                var created = base.plusDays(i % 2).plusSeconds(i);
                // Durations a millisecond apart, all distinct, so a percentile cannot be right by
                // rounding and an average cannot be right by accident.
                //
                // Milliseconds and not nanoseconds: PostgreSQL keeps timestamps to the microsecond,
                // so nanosecond offsets are truncated on the way in. An earlier version of this
                // seed varied by 1 ns, every duration came back as exactly 1 s, and the test passed
                // against percentile_disc(0.5) — a distribution of one value has no percentiles to
                // get wrong.
                var finished = i % 7 == 0 ? null : created.plusSeconds(1).plusNanos(i * 1_000_000L);
                // Every seventh has no started, so the fallback to created is exercised.
                var started = i % 7 == 0 ? null : created;
                var status = finished == null ? ProcessStatus.RUNNING
                        : (i % 5 == 0 ? ProcessStatus.ERROR : ProcessStatus.COMPLETED);

                processRows.add(new ProcessEntity(id, id, definition + " name", "[]", status.name(),
                        finished == null ? 50 : 100, "log", definition, 1, "{}",
                        // version null, not 0: Spring Data reads a null version as "never
                        // persisted" and inserts. A zero means update, and there is nothing to update.
                        created, started, finished, null, null, null));

                for (var s = 0; s < 3; s++) {
                    var stepStarted = created.plusNanos(s * 1_000L);
                    // The last step of an unfinished process never finished: it counts, and
                    // contributes no duration.
                    LocalDateTime stepFinished = (finished == null && s == 2) ? null
                            : stepStarted.plusNanos(((10L + s) + i) * 1_000_000L);
                    var stepStatus = stepFinished == null ? StepExecutionStatus.RUNNING
                            : StepExecutionStatus.COMPLETED;
                    stepRows.add(stepExecution(id + "-s" + s, id, definition, "step-" + s,
                            stepStatus, s, stepStarted, stepFinished));
                }
            }
        }
        processEntities.saveAll(processRows);
        stepEntities.saveAll(stepRows);
    }

    private static StepExecutionEntity stepExecution(String id, String processId, String definitionId,
                                                     String stepId, StepExecutionStatus status,
                                                     long order, LocalDateTime startedAt,
                                                     LocalDateTime finishedAt) {
        var entity = new StepExecutionEntity();
        entity.setId(id);
        entity.setProcessId(processId);
        entity.setWorkflowDefinitionId(definitionId);
        entity.setStepId(stepId);
        entity.setStatus(status.name());
        entity.setOrder(order);
        entity.setStartedAt(startedAt);
        entity.setFinishedAt(finishedAt);
        entity.setAttemptCount(1);
        return entity;
    }
}
