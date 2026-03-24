package io.mateu.workflow.contentservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentTypeEntityRepository extends JpaRepository<ContentTypeEntity, Long> {
Page<ContentTypeEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
    }
