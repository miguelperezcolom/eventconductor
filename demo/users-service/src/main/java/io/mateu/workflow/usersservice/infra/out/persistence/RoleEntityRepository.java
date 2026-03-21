package io.mateu.workflow.usersservice.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleEntityRepository extends JpaRepository<RoleEntity, String> {
}
