package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.LogMessage;

import java.util.List;

public interface LogMessageRepository extends CrudStore<LogMessage> {

    List<LogMessage> findByProcessId(String processId);

}
