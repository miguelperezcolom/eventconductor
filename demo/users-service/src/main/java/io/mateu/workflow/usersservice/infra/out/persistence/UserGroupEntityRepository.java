package io.mateu.workflow.usersservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGroupEntityRepository extends JpaRepository<UserGroupEntity, String> {
    Page<UserGroupEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
}
