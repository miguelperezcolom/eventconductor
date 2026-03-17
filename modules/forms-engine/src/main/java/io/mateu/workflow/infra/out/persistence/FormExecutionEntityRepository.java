package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FormExecutionEntityRepository extends JpaRepository<FormExecutionEntity, String> {
}
