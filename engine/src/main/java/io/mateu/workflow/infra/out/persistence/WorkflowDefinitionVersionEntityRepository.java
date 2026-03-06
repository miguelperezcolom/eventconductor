package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.repository.CrudRepository;

public interface WorkflowDefinitionVersionEntityRepository extends CrudRepository<WorkflowDefinitionVersionEntity, String> {
}
