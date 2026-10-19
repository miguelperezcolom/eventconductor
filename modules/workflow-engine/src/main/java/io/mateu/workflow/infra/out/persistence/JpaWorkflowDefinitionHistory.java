package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowDefinitionHistory;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/** {@link WorkflowDefinitionHistory} over the append-only version table the versioning service writes. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaWorkflowDefinitionHistory implements WorkflowDefinitionHistory {

    private final WorkflowDefinitionVersionEntityRepository versions;

    @Override
    public Optional<WorkflowDefinition> find(String definitionId, int version) {
        return versions.findByDefinitionIdAndVersion(definitionId, version)
                .map(recorded -> pojoFromJson(recorded.getSnapshotJson(), WorkflowDefinition.class).withVersion(version));
    }

    @Override
    public List<Integer> versions(String definitionId) {
        return versions.findByDefinitionIdOrderByVersionDesc(definitionId).stream()
                .map(WorkflowDefinitionVersionEntity::getVersion).toList();
    }
}
