package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ProcessEntityRepository extends JpaRepository<ProcessEntity, String> {

    Optional<ProcessEntity> findByBusinessKey(String businessKey);

}
