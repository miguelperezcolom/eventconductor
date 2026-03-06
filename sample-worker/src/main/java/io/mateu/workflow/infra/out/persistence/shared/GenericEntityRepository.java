package io.mateu.workflow.infra.out.persistence.shared;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface GenericEntityRepository extends JpaRepository<GenericEntity, String> {

    Collection<GenericEntity> findByType(String type);

    Collection<GenericEntity> findAllByTypeAndNameContainingIgnoreCase(String type, String name);

    Collection<GenericEntity> findByIdAndType(String id, String type);
}
