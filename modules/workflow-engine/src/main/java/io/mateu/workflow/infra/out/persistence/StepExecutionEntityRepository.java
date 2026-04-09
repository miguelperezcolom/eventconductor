package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepExecutionEntityRepository extends JpaRepository<StepExecutionEntity, String> {
    List<StepExecutionEntity> findAllByProcessIdOrderByOrder(String processId);
}
