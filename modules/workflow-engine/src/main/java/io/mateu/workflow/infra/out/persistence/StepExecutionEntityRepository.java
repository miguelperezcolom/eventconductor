package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.aggregates.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface StepExecutionEntityRepository extends JpaRepository<StepExecutionEntity, String> {
    List<StepExecution> findAllByProcessId(String processId);
}
