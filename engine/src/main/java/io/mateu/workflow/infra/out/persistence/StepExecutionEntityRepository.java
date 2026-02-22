package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.StepExecutionStatus;
import org.springframework.data.repository.CrudRepository;

public interface StepExecutionEntityRepository extends CrudRepository<StepExecutionEntity, String> {
}
