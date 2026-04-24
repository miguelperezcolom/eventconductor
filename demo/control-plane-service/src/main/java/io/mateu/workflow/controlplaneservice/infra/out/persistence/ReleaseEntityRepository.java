package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseEntityRepository extends JpaRepository<ReleaseEntity, Long> {
    Page<ReleaseEntity> findAllByNameContainingIgnoreCaseOrderByIdDesc(String searchText, Pageable pageable);
}
