package io.mateu.workflow.infra.out.persistence;

import java.time.LocalDateTime;

/**
 * The projections the read model's queries return. The queries themselves are the package-private
 * repositories beside this class: the windowed reads of the rollup tables
 * ({@link ProcessCreatedDailyRepository} and its siblings), the cursored reads of the raw tables the
 * projector folds from ({@link AnalyticsSourceRepository}), and the two live-overlay reads of what is
 * still in flight ({@link LiveOverlayRepository}). All package-private — this is one feature's data
 * access.
 *
 * <p>The repositories are top-level interfaces, not members of this class, because repository
 * scanning ({@code @EnableJpaRepositories} and Spring Boot's auto-configuration alike) skips nested
 * interfaces unless {@code considerNestedRepositories} is set — which is the host application's
 * configuration, not the engine's. Nested, they were never created, and a context with
 * {@code workflow.analytics.rollup=true} failed to start.
 *
 * <p>The windowed reads filter on the creation day and return whole rows, because a 30-day window is
 * a few dozen of them; the reducer does the last grouping in memory. The cursored source reads are
 * ordered by {@code (timestamp, id)} and paged, so the projector walks each stream in bounded
 * batches and never holds the table.
 */
final class AnalyticsRollupRepositories {

    private AnalyticsRollupRepositories() {
    }

    // ─────────────────────────── projector source: cursored reads of the raw tables ───────────────────────────

    interface ProcessCreationSourceView {
        String getId();
        String getWorkflowDefinitionId();
        LocalDateTime getCreated();
    }

    interface ProcessFinishSourceView {
        String getId();
        String getWorkflowDefinitionId();
        String getStatus();
        String getName();
        LocalDateTime getCreated();
        LocalDateTime getStarted();
        LocalDateTime getFinished();
    }

    interface StepFinishSourceView {
        String getId();
        String getWorkflowDefinitionId();
        String getStepId();
        String getStatus();
        long getStepOrder();
        LocalDateTime getStartedAt();
        LocalDateTime getFinishedAt();
        LocalDateTime getProcessCreated();
    }

    // ─────────────────────────── live overlay: what is still in flight, read straight ───────────────────────────

    interface LiveProcessStatusView {
        String getDefinitionId();
        String getStatus();
        long getCount();
        String getAnyName();
    }

    interface LiveStepStatusView {
        String getDefinitionId();
        String getStepId();
        String getStatus();
        long getCount();
        long getFirstOrder();
    }
}
