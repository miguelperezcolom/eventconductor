package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface FieldEntityRepository extends JpaRepository<FieldEntity, String> {

    List<FieldEntity> findByFormId(String formId);

}
