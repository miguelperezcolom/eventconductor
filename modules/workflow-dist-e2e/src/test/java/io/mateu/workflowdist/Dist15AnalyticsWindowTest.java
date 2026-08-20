package io.mateu.workflowdist;

import io.mateu.workflow.infra.out.persistence.ProcessEntityRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DIST-15 — The analytics window queries run on PostgreSQL, with and without bounds.
 *
 * <p>They did not. Both analytics projections bound their window with the usual optional-parameter
 * shape, {@code (:createdFrom is null or p.created >= :createdFrom)} — and Hibernate emits a
 * <em>separate</em> placeholder per occurrence of a named parameter, so the SQL that reaches the
 * database reads {@code (? is null or pe1_0.created >= ?)} and {@code $1} appears nowhere except
 * in {@code $1 is null}. PostgreSQL has nothing to infer its type from and refuses to prepare the
 * statement at all: {@code SQLState 42P18, could not determine data type of parameter $1}. The
 * unbounded window is the page's own default, so {@code /workflow/analytics} returned 500 for
 * everyone, on every deployment backed by PostgreSQL.
 *
 * <p>It survived release because nothing exercised these two queries against PostgreSQL:
 * {@code workflow-engine}'s tests run on H2, which infers the type happily and returns rows. The
 * failure needs a real database, so the test that covers it belongs here rather than beside the
 * repository — and it needs no processes, no worker and no Kafka traffic, because a statement that
 * cannot be prepared fails on an empty table exactly as it does on a full one.
 *
 * <p>Both bound and unbounded windows are exercised: the cast fixes the null branch, and the other
 * branch has to keep working with a value in it.
 */
class Dist15AnalyticsWindowTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;
    static ProcessEntityRepository processes;
    static StepExecutionEntityRepository steps;

    @BeforeAll
    static void startPod() {
        orchestrator = DistInfra.startOrchestrator(Map.of());
        processes = orchestrator.getBean(ProcessEntityRepository.class);
        steps = orchestrator.getBean(StepExecutionEntityRepository.class);
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void unboundedWindowPrepares() {
        assertThatCode(() -> processes.findAnalyticsRows(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> steps.findAnalyticsRows(null, null)).doesNotThrowAnyException();
    }

    @Test
    void halfBoundedWindowsPrepare() {
        var when = LocalDateTime.now().minusDays(1);

        assertThatCode(() -> processes.findAnalyticsRows(when, null)).doesNotThrowAnyException();
        assertThatCode(() -> processes.findAnalyticsRows(null, when)).doesNotThrowAnyException();
        assertThatCode(() -> steps.findAnalyticsRows(when, null)).doesNotThrowAnyException();
        assertThatCode(() -> steps.findAnalyticsRows(null, when)).doesNotThrowAnyException();
    }

    @Test
    void boundedWindowPrepares() {
        var from = LocalDateTime.now().minusDays(1);
        var to = LocalDateTime.now().plusDays(1);

        assertThatCode(() -> processes.findAnalyticsRows(from, to)).doesNotThrowAnyException();
        assertThatCode(() -> steps.findAnalyticsRows(from, to)).doesNotThrowAnyException();
    }
}
