package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.annotations.Hidden;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class WorkflowDefinitionEntity {
    @Id
    private String id;

    String name;

    int version;

    String description;

    @Column(columnDefinition = "TEXT")
    String stepsJson;

    boolean limitConcurrentExecutions;

    int maxConcurrentExecutions;

    boolean enqueueOnLimit;

    String cronExpression;

    @ColumnDefault("0")
    int defaultMaxStepExecutions;

    /**
     * Cap on total step executions per process instance, runtime injections included. 0 (the
     * default) falls back to the engine-wide {@code workflow.dynamic.max-steps-per-process}.
     */
    @ColumnDefault("0")
    int maxSteps;

    /** Runtime pause flag: while true all this definition's processes are held. */
    @ColumnDefault("false")
    boolean paused;

/**
     * What the definition file declares, and what an operator has decided, as one word each.
     *
     * <p>Two booleans could say four things when three are meaningful, and left "is an archived
     * workflow also disabled?" to prose. Stored apart because they answer to different people:
     * the file to whoever writes the workflow, the runtime to whoever operates it. A workflow is
     * open for business only if both say so.
     */
    @ColumnDefault("'ACTIVE'")
    String declaredStatus;

    @ColumnDefault("'ACTIVE'")
    String runtimeStatus;

    /**
     * The diagram arrangement the author made by hand, as the JSON object the .ec file carries
     * under {@code layout}: step id to {x, y}.
     *
     * <p>Null when nobody arranged this workflow, which is what rows written before the column
     * existed read as, and what makes the graph lay itself out. Kept as JSON rather than as a table
     * of its own because nothing queries it — it is read whole, with the definition, or not at all.
     */
    @Column(columnDefinition = "TEXT")
    String layoutJson;

    /**
     * The process-level serialization lock as the {@code .ec} file carries it under
     * {@code processLock}: {@code {name, key}}, or null when the definition declares none. JSON for
     * the same reason as {@code layoutJson} — read whole with the definition, never queried.
     */
    @Column(columnDefinition = "TEXT")
    String processLockJson;

    /**
     * The synchronous-invocation configuration as the {@code .ec} file carries it under
     * {@code syncInvocation}, or null when the definition is not sync-invocable. JSON, like
     * {@code processLockJson}.
     */
    @Column(columnDefinition = "TEXT")
    String syncInvocationJson;

    /**
     * The definition's {@code schedules} as the {@code .ec} file carries them, or null when it
     * declares none. JSON, like {@code processLockJson}: read whole with the definition.
     */
    @Column(columnDefinition = "TEXT")
    String schedulesJson;

    /**
     * The definition's event {@code triggers} as the {@code .ec} file carries them, or null when it
     * declares none. JSON, like {@code schedulesJson}.
     */
    @Column(columnDefinition = "TEXT")
    String triggersJson;

    /** The definition's {@code tags} as a JSON array, or null when it declares none (V41). */
    @Column(columnDefinition = "TEXT")
    String tagsJson;

    /**
     * The definition's {@code retention} as the file writes it — a JSON string ({@code "P30D"}) or an
     * object per terminal status — or null when it declares none (V45). Metadata, like the tags.
     */
    @Column(columnDefinition = "TEXT")
    String retentionJson;

    /**
     * The definition's {@code searchAttributes} as the file writes them (a JSON object of name → template
     * or {value, type}), or null when it declares none (V48). Part of the version, unlike the tags.
     */
    @Column(columnDefinition = "TEXT")
    String searchAttributesJson;

    /**
     * The sensitive variables, as the file writes them (a JSON array of names or globs), or null when it
     * declares none (V53). Part of the version.
     */
    @Column(columnDefinition = "TEXT")
    String sensitiveVariablesJson;

}
