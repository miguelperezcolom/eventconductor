package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** The process index's search-attribute rows (embedded index, jpa persistence). */
public interface ProcessIndexSearchAttributeEntityRepository
        extends JpaRepository<ProcessIndexSearchAttributeEntity, ProcessIndexSearchAttributeEntity.Key> {

    List<ProcessIndexSearchAttributeEntity> findAllByProcessId(String processId);

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("delete from ProcessIndexSearchAttributeEntity a where a.processId in :processIds")
    int deleteAllByProcessIdIn(@Param("processIds") Collection<String> processIds);
}
