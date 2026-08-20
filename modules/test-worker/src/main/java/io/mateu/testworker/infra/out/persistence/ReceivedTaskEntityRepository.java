package io.mateu.testworker.infra.out.persistence;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ReceivedTaskEntityRepository
        extends JpaRepository<ReceivedTaskEntity, String>, JpaSpecificationExecutor<ReceivedTaskEntity> {

    List<ReceivedTaskEntity> findAll(Sort sort);
}
