package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import com.google.common.io.Files;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetEntityRepository extends JpaRepository<AssetEntity, Long> {
    Page<AssetEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);

    Optional<AssetEntity> findByUrlAndCountryCode(String url, String code);
}
