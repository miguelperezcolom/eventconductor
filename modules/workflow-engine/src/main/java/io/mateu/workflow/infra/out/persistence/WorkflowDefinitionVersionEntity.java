package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One immutable row per recorded version of a workflow definition. A new row is written only when
 * the definition's content changes (see {@code WorkflowDefinitionVersioningService}); it freezes the
 * whole definition as JSON so the version's diagram can be rendered exactly as it was, independent of
 * the current head. Kept append-only — the head row in {@code WorkflowDefinitionEntity} always points
 * at the latest {@code version}.
 */
@Entity
@Table(name = "workflow_definition_version",
        indexes = @Index(name = "idx_wf_def_version_def", columnList = "definitionId, version"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinitionVersionEntity {

    /** {@code "<definitionId>:<version>"} — stable and unique, so a race on the same version fails. */
    @Id
    private String id;

    private String definitionId;

    private int version;

    private String name;

    /** Frozen full-definition JSON snapshot, as {@code toJson(definition)} at record time. */
    @Column(columnDefinition = "TEXT")
    private String snapshotJson;

    /** SHA-256 of the version-relevant content (steps + config), used to detect real changes. */
    private String contentHash;

    private LocalDateTime createdAt;
}
