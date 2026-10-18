package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

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
 *
 * <p>Each carries an explicit {@code @Entity(name)}: left to the default, Hibernate names a nested
 * entity after its binary name ({@code AnalyticsRollupEntities$ProcessCreatedDailyEntity}), and the
 * repositories' JPQL, which calls them by their simple names, fails validation at startup.
 */
final class AnalyticsRollupEntities {

    private AnalyticsRollupEntities() {
    }

    @Entity(name = "ProcessCreatedDailyEntity")
    @Table(name = "process_created_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class ProcessCreatedDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        // Column is created_day: `day` is a reserved word in H2, which the embedded/test schema uses.
        @Column(name = "created_day") private LocalDate day;
        private long cnt;
    }

    @Entity(name = "ProcessFinishedDailyEntity")
    @Table(name = "process_finished_daily")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    static class ProcessFinishedDailyEntity {
        @Id private String k;
        private String workflowDefinitionId;
        private LocalDate createdDay;
        private LocalDate finishedDay;
        private long cnt;
    }

    @Entity(name = "ProcessStatusDailyEntity")
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

    @Entity(name = "ProcessDurationDailyEntity")
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

    @Entity(name = "StepStatusDailyEntity")
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

    @Entity(name = "StepDurationDailyEntity")
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

    @Entity(name = "AnalyticsProjectionStateEntity")
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
    /**
     * What the projector added to the rollups for one finished process — the ledger entry that lets
     * it take that contribution back out when the process is retried or restarted (V43).
     *
     * <p>{@link Persistable} so a first-time entry is inserted without the select a {@code merge}
     * of an assigned id would issue: the projector already looked the batch's ids up, so it knows.
     */
    @Entity(name = "CountedProcessEntity")
    @Table(name = "analytics_counted_process")
    @Getter @Setter @NoArgsConstructor
    static class CountedProcessEntity implements Persistable<String> {
        @Id private String processId;
        private String workflowDefinitionId;
        private LocalDate createdDay;
        private LocalDate finishedDay;
        private String status;
        private Long durationNanos;
        @Transient private boolean fresh;

        CountedProcessEntity(String processId, String workflowDefinitionId, LocalDate createdDay,
                             LocalDate finishedDay, String status, Long durationNanos) {
            this.processId = processId;
            this.workflowDefinitionId = workflowDefinitionId;
            this.createdDay = createdDay;
            this.finishedDay = finishedDay;
            this.status = status;
            this.durationNanos = durationNanos;
            this.fresh = true;
        }

        @Override
        public String getId() {
            return processId;
        }

        @Override
        public boolean isNew() {
            return fresh;
        }

        @PostLoad
        @PostPersist
        void stored() {
            fresh = false;
        }
    }

    /** What the projector added to the rollups for one finished step execution — see above. */
    @Entity(name = "CountedStepEntity")
    @Table(name = "analytics_counted_step")
    @Getter @Setter @NoArgsConstructor
    static class CountedStepEntity implements Persistable<String> {
        @Id private String stepExecutionId;
        private String workflowDefinitionId;
        private String stepId;
        private LocalDate createdDay;
        private String status;
        private long stepOrder;
        private Long durationNanos;
        @Transient private boolean fresh;

        CountedStepEntity(String stepExecutionId, String workflowDefinitionId, String stepId,
                          LocalDate createdDay, String status, long stepOrder, Long durationNanos) {
            this.stepExecutionId = stepExecutionId;
            this.workflowDefinitionId = workflowDefinitionId;
            this.stepId = stepId;
            this.createdDay = createdDay;
            this.status = status;
            this.stepOrder = stepOrder;
            this.durationNanos = durationNanos;
            this.fresh = true;
        }

        @Override
        public String getId() {
            return stepExecutionId;
        }

        @Override
        public boolean isNew() {
            return fresh;
        }

        @PostLoad
        @PostPersist
        void stored() {
            fresh = false;
        }
    }
}
