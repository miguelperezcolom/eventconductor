package io.mateu.workflow.usersservice.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGroupEntityRepository extends JpaRepository<UserGroupEntity, String> {
}
