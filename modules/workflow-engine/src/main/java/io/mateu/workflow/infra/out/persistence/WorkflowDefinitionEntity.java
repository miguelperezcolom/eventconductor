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

    /** Runtime pause flag: while true all this definition's processes are held. */
    @ColumnDefault("false")
    boolean paused;

    /** Runtime disabled flag: while true the definition accepts no new instances. */
    @ColumnDefault("false")
    boolean disabled;

    /** Runtime archived flag: set by the git-import prune to hide a removed definition. */
    @ColumnDefault("false")
    boolean archived;

    /**
     * What the definition file declares, kept apart from the runtime flags above: the file is a
     * floor the runtime cannot lift, and mixing the two in one column is what let every import
     * overwrite an operator's decision — and an operator overwrite the file's.
     */
    @ColumnDefault("false")
    boolean declaredDisabled;

    @ColumnDefault("false")
    boolean declaredArchived;

}
