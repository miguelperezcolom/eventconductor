package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
