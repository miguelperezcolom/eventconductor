package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;

import java.util.List;

public interface StepExecutionRepository extends CrudRepository<StepExecution> {

    List<StepExecution> findByProcess(Process process);

    List<StepExecution> findPendingOrRunning();

}
