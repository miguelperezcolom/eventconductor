package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The analytics read model's tables, as entities — one per rollup, keyed by a surrogate string
 * {@code k} the projector builds from the natural key so an upsert is a {@code findById} then add.
 *
 * <p>Kept together and package-private on purpose: they are storage for one feature, written only by
 * {@code AnalyticsRollupProjector} and read only by {@code RollupAnalyticsReader}, and nothing
 * outside this package has any business holding one. The {@code @Table} names match V27 exactly so a
 * ddl-auto deployment and a migrated one land on the same schema.
 */
final class AnalyticsRollupEntities {

    private AnalyticsRollupEntities() {
    }

    @Entity
    @Table(name = "process_created_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class ProcessCreatedDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        // Column is created_day: `day` is a reserved word in H2, which the embedded/test schema uses.
        @Column(name = "created_day") private LocalDate day;
        private long cnt;
    }

    @Entity
    @Table(name = "process_finished_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class ProcessFinishedDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        private LocalDate createdDay;
        private LocalDate finishedDay;
        private long cnt;
    }

    @Entity
    @Table(name = "process_status_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class ProcessStatusDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        private LocalDate createdDay;
        private String status;
        private long cnt;
        private String anyName;
    }

    @Entity
    @Table(name = "process_duration_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class ProcessDurationDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        private LocalDate createdDay;
        private long samples;
        private long totalNanos;
        @Column(columnDefinition = "TEXT") private String histogram;
    }

    @Entity
    @Table(name = "step_status_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class StepStatusDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        private String stepId;
        private LocalDate createdDay;
        private String status;
        private long cnt;
        private long firstOrder;
    }

    @Entity
    @Table(name = "step_duration_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class StepDurationDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        private String stepId;
        private LocalDate createdDay;
        private long samples;
        private long totalNanos;
        @Column(columnDefinition = "TEXT") private String histogram;
    }

    @Entity
    @Table(name = "analytics_projection_state")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class AnalyticsProjectionStateEntity {
        @Id private Integer id;
        private LocalDateTime createdCursorTs;
        private String createdCursorId;
        private LocalDateTime pfinishedCursorTs;
        private String pfinishedCursorId;
        private LocalDateTime sfinishedCursorTs;
        private String sfinishedCursorId;
    }
}
