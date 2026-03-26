package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryEntityRepository extends JpaRepository<CountryEntity, String> {
Page<CountryEntity> findAllByNameContainingIgnoreCase(String searchText, Pageable pageable);
    }
