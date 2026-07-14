package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleEntityRepository extends JpaRepository<RuleEntity, String> {
}
