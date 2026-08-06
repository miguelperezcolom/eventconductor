package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FieldEntityRepository extends JpaRepository<FieldEntity, FieldEntity.Key> {

    List<FieldEntity> findByFormIdOrderByFieldOrderAsc(String formId);

    /**
     * Bulk deletes, rather than a derived {@code deleteByFormId}, because the caller replaces a
     * form's fields inside one transaction: Hibernate's action queue flushes inserts before deletes,
     * so a derived delete would run <em>after</em> the re-inserted rows and take them with it.
     * {@code @Modifying} issues the statement immediately instead.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM FieldEntity f WHERE f.formId = :formId")
    void deleteByFormId(@Param("formId") String formId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM FieldEntity f WHERE f.formId IN :formIds")
    void deleteByFormIdIn(@Param("formIds") List<String> formIds);

}
