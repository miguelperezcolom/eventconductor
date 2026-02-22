package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.repository.CrudRepository;

public interface ProcessEntityRepository extends CrudRepository<ProcessEntity, String> {
}
