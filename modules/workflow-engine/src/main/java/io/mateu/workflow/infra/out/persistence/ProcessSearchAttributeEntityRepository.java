package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ProcessSearchAttributeEntityRepository
        extends JpaRepository<ProcessSearchAttributeEntity, ProcessSearchAttributeEntity.Key> {

    List<ProcessSearchAttributeEntity> findAllByProcessId(String processId);

    List<ProcessSearchAttributeEntity> findAllByProcessIdIn(Collection<String> processIds);

    @Transactional
    @Modifying
    @Query("delete from ProcessSearchAttributeEntity a where a.processId in :processIds")
    int deleteAllByProcessIdIn(@Param("processIds") Collection<String> processIds);
}
