package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.repository.CrudRepository;

public interface FieldEntityRepository extends CrudRepository<FormEntity, String> {
}
