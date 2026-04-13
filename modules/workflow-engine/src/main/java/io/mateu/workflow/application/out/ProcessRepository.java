package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.Process;

import java.util.Optional;

public interface ProcessRepository extends CrudRepository<Process> {

    Optional<Process> findByBusinessKey(String businessKey);

}
