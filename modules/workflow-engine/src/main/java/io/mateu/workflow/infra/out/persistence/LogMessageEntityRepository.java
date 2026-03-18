package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogMessageEntityRepository extends JpaRepository<LogMessageEntity, String> {
    List<LogMessageEntity> findAllByProcessId(String processId);
}
