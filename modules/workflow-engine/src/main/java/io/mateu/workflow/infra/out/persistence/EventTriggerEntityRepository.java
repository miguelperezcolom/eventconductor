package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventTriggerEntityRepository extends JpaRepository<EventTriggerEntity, String> {

    List<EventTriggerEntity> findBySourceKey(String sourceKey);

    @Modifying
    @Query("delete from EventTriggerEntity t where t.sourceKey = :sourceKey")
    void deleteBySourceKey(@Param("sourceKey") String sourceKey);
}
