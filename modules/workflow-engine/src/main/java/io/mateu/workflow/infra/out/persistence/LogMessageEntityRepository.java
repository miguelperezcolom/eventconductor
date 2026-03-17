package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LogMessageEntityRepository extends JpaRepository<LogMessage, String> {
}
