package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteEntityRepository extends JpaRepository<SiteEntity, String> {
Page<SiteEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
    }
