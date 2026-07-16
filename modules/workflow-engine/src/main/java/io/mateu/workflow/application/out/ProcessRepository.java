package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.util.Optional;

public interface ProcessRepository extends CrudRepository<Process> {

    Optional<Process> findByBusinessKey(String businessKey);

    default long countByStatus(ProcessStatus status) {
        return findAll().stream().filter(process -> status.equals(process.getStatus())).count();
    }

}
