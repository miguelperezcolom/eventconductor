package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskContractEntityRepository extends JpaRepository<TaskContractEntity, String> {

    Optional<TaskContractEntity> findFirstByContractIdOrderByVersionDesc(String contractId);

    Optional<TaskContractEntity> findByContractIdAndVersion(String contractId, int version);
}
