package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;

import java.util.List;

public interface LogMessageRepository extends CrudRepository<LogMessage> {

    List<LogMessage> findByProcessId(String processId);

}
