package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionVersionEntityRepository
        extends JpaRepository<WorkflowDefinitionVersionEntity, String> {

    /** The latest recorded version of a definition, or empty if none has been recorded yet. */
    Optional<WorkflowDefinitionVersionEntity> findTopByDefinitionIdOrderByVersionDesc(String definitionId);

    /** All recorded versions of a definition, newest first. */
    List<WorkflowDefinitionVersionEntity> findByDefinitionIdOrderByVersionDesc(String definitionId);

    Optional<WorkflowDefinitionVersionEntity> findByDefinitionIdAndVersion(String definitionId, int version);
}
