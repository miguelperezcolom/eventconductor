package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceEntityRepository extends JpaRepository<ResourceEntity, Long> {
Page<ResourceEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
    }
