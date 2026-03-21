package io.mateu.workflow.usersservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEntityRepository extends JpaRepository<UserEntity, String> {
    Page<UserEntity> findAllByNameContainingIgnoreCase(String name, Pageable pageable);
}
