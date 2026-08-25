package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProcessIndexEntityRepository extends JpaRepository<ProcessIndexEntity, String> {

    List<ProcessIndexEntity> findAllByStatusIn(List<String> statuses);

    List<ProcessIndexEntity> findAllByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                      List<String> statuses);

    Optional<ProcessIndexEntity> findFirstByBusinessKey(String businessKey);

    /**
     * Counts per status in the database, which is the only place it can be counted.
     *
     * <p>This used to be {@code findAll()} grouped in Java: every row of the index materialised as
     * an entity so that the result could be thrown away and replaced by a handful of numbers. It is
     * reachable from the MCP tools and the read-model query service, so "how many processes are
     * running" was one question away from loading the whole table into the heap — at the scale this
     * read model exists for, that is not a slow query, it is an OutOfMemoryError.
     */
    @org.springframework.data.jpa.repository.Query(
            "select e.status, count(e) from ProcessIndexEntity e group by e.status")
    List<Object[]> countGroupedByStatus();

    /**
     * One page of the operator listing. Filters, orders and pages in the database — the listing on
     * the write side does the same against {@code process_entity}, and answering it here from a
     * loaded list would put back exactly the cost that was taken out of it.
     *
     * <p>{@code :pattern} is a pre-lowercased LIKE pattern or null for "no text filter", matched
     * against name and business key the way the write-side listing matches them.
     */
    @Query("""
            select p from ProcessIndexEntity p
            where (:onlyErrors = false or p.status = 'ERROR')
              and (:pattern is null
                   or lower(concat(coalesce(p.name, ''), ' ', coalesce(p.businessKey, ''))) like :pattern)
            order by p.created desc nulls last
            """)
    List<ProcessIndexEntity> search(@Param("onlyErrors") boolean onlyErrors,
                                    @Param("pattern") String pattern,
                                    Pageable pageable);

    /** How many rows the same filter matches — the caller needs the total before it knows which page exists. */
    @Query("""
            select count(p) from ProcessIndexEntity p
            where (:onlyErrors = false or p.status = 'ERROR')
              and (:pattern is null
                   or lower(concat(coalesce(p.name, ''), ' ', coalesce(p.businessKey, ''))) like :pattern)
            """)
    long countSearch(@Param("onlyErrors") boolean onlyErrors, @Param("pattern") String pattern);
}
