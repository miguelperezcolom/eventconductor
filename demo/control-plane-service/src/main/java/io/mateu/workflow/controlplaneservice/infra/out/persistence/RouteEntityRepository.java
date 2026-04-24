package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RouteEntityRepository extends JpaRepository<RouteEntity, Long> {
    Page<RouteEntity> findAllByNameContainingIgnoreCaseOrderByName(String searchText, Pageable pageable);

    List<RouteEntity> findAllByOrderByPath();
}
