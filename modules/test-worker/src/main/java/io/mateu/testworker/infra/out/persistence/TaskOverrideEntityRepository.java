package io.mateu.testworker.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskOverrideEntityRepository extends JpaRepository<TaskOverrideEntity, String> {

    List<TaskOverrideEntity> findByEnabledTrue();
}
