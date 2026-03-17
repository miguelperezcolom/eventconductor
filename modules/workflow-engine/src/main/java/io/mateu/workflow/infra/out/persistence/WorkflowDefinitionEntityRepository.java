package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowDefinitionEntityRepository extends JpaRepository<WorkflowDefinitionEntity, String> {

    @Override
    Page<WorkflowDefinitionEntity> findAll(Pageable pageable);
}
