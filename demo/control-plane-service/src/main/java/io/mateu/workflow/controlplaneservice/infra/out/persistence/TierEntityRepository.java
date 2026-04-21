package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierEntityRepository extends JpaRepository<TierEntity, String> {
    Page<TierEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
}
