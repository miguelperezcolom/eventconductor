package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageEntityRepository extends JpaRepository<PageEntity, Long> {
    Page<PageEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);

    List<PageEntity> findBySiteId(String id);
}
