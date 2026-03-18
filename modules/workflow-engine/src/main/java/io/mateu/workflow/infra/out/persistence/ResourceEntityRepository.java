package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ResourceEntityRepository extends JpaRepository<ResourceEntity, String> {
    List<ResourceEntity> findAllByProcessId(String processId);
}
