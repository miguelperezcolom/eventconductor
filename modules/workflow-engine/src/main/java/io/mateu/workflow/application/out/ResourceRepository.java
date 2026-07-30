package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.Resource;

import java.util.List;

public interface ResourceRepository extends CrudStore<Resource> {

    List<Resource> findByProcessId(String processId);

}
