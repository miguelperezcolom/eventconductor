package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LanguageEntityRepository extends JpaRepository<LanguageEntity, Long> {
Page<LanguageEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
    }
