package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.repository.CrudRepository;

public interface WorkflowDefinitionEntityRepository extends CrudRepository<WorkflowDefinitionEntity, String> {
}
