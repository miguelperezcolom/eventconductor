package io.mateu.workflow.contentservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabelEntityRepository extends JpaRepository<LabelEntity, Long> {
Page<LabelEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
    }
