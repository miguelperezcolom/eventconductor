package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessIndexEntityRepository extends JpaRepository<ProcessIndexEntity, String> {

    List<ProcessIndexEntity> findAllByStatusIn(List<String> statuses);

    List<ProcessIndexEntity> findAllByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                      List<String> statuses);

    Optional<ProcessIndexEntity> findFirstByBusinessKey(String businessKey);
}
