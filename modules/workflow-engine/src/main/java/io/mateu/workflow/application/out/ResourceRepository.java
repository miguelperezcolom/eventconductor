package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.Resource;

import java.util.List;

public interface ResourceRepository extends CrudRepository<Resource> {

    List<Resource> findByProcessId(String processId);

}
