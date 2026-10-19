package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.paging.ServedPage;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Process-index read store on the relational database (jpa persistence). */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
public class ProcessIndexDBRepository implements ProcessIndexRepository {

    private final ProcessIndexEntityRepository repository;
    private final ProcessIndexSearchAttributeEntityRepository attributes;

    /**
     * The engine's own persistence unit — where the embedded index lives. Final and constructor-injected,
     * never serialized (see ProcessDBRepository).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final jakarta.persistence.EntityManager entityManager;

    public ProcessIndexDBRepository(ProcessIndexEntityRepository repository,
                                    ProcessIndexSearchAttributeEntityRepository attributes,
                                    jakarta.persistence.EntityManager entityManager) {
        this.repository = repository;
        this.attributes = attributes;
        this.entityManager = entityManager;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void upsert(ProcessIndexRow row) {
        // save() is an upsert on the @Id (processId). Out-of-order redelivery is guarded: skip a
        // write whose event is older than what is already stored.
        var existing = repository.findById(row.processId()).orElse(null);
        if (existing != null && existing.getUpdatedAt() != null && row.updatedAt() != null
                && row.updatedAt().isBefore(existing.getUpdatedAt())) {
            return;
        }
        repository.save(new ProcessIndexEntity(
                row.processId(), row.businessKey(), row.name(), row.workflowDefinitionId(),
                row.workflowDefinitionVersion(), row.status(), row.completionPercentage(),
                row.created(), row.started(), row.finished(), row.updatedAt(), row.shardId()));
        // An event that carries attributes replaces them; one that carries none (null) leaves them.
        if (row.searchAttributes() != null) {
            attributes.deleteAll(attributes.findAllByProcessId(row.processId()));
            attributes.flush();
            attributes.saveAll(row.searchAttributes().stream()
                    .map(value -> new ProcessIndexSearchAttributeEntity(row.processId(), value)).toList());
        }
    }

    /** The search attributes the index holds for a process — for tests and diagnosis. */
    public List<io.mateu.workflow.search.SearchAttributeValue> searchAttributesOf(String processId) {
        return attributes.findAllByProcessId(processId).stream()
                .map(ProcessIndexSearchAttributeEntity::toValue).toList();
    }

    @Override
    public List<ProcessIndexRow> findByStatusIn(Collection<String> statuses) {
        return repository.findAllByStatusIn(List.copyOf(statuses)).stream().map(this::toRow).toList();
    }

    @Override
    public List<ProcessIndexRow> findByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                       Collection<String> statuses) {
        return repository.findAllByWorkflowDefinitionIdAndStatusIn(workflowDefinitionId, List.copyOf(statuses))
                .stream().map(this::toRow).toList();
    }

    @Override
    public Optional<ProcessIndexRow> findByBusinessKey(String businessKey) {
        return repository.findFirstByBusinessKey(businessKey).map(this::toRow);
    }

    @Override
    public Optional<ProcessIndexRow> findByProcessId(String processId) {
        return repository.findById(processId).map(this::toRow);
    }

    @Override
    public Map<String, Long> countByStatus() {
        return repository.countGroupedByStatus().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));
    }

    /**
     * Counted first, then the page — which page can be served depends on how many there are, and
     * the listing's contract is that a request past the end is answered with the last real page.
     */
    @Override
    public java.util.Optional<ProcessIndexPage> search(String searchText, boolean onlyErrors,
                                                       int page, int size) {
        var pattern = (searchText == null || searchText.isBlank())
                ? null : "%" + searchText.toLowerCase() + "%";
        var total = repository.countSearch(onlyErrors, pattern);
        var served = ServedPage.of(page, size, total);
        var content = repository
                .search(onlyErrors, pattern,
                        org.springframework.data.domain.PageRequest.of(served.number(), served.size()))
                .stream().map(this::toRow).toList();
        return java.util.Optional.of(
                new ProcessIndexPage(content, total, served.number(), served.size()));
    }

    /**
     * The listing narrowed by search attributes too: the same text and errors conditions as
     * {@link #search(String, boolean, int, int)}, plus one {@code EXISTS} per attribute condition — built
     * as JPQL with every value bound (see {@code SearchAttributeConditions}).
     */
    @Override
    public java.util.Optional<ProcessIndexPage> search(String searchText, boolean onlyErrors,
                                                       List<io.mateu.workflow.search.SearchAttributeFilter> filters,
                                                       int page, int size) {
        if (filters == null || filters.isEmpty()) {
            return search(searchText, onlyErrors, page, size);
        }
        var pattern = (searchText == null || searchText.isBlank())
                ? null : "%" + searchText.toLowerCase() + "%";
        var where = new StringBuilder(" where 1 = 1");
        var params = new java.util.LinkedHashMap<String, Object>();
        if (onlyErrors) {
            where.append(" and p.status = 'ERROR'");
        }
        if (pattern != null) {
            where.append(" and lower(concat(coalesce(p.name, ''), ' ', coalesce(p.businessKey, ''))) like :pattern");
            params.put("pattern", pattern);
        }
        var conditions = io.mateu.workflow.processindex.SearchAttributeConditions.of(
                io.mateu.workflow.processindex.SearchAttributeConditions.Dialect.JPQL,
                "ProcessIndexSearchAttributeEntity", "p.processId", filters, "a");
        where.append(conditions.text());
        params.putAll(conditions.named());
        var count = entityManager.createQuery("select count(p) from ProcessIndexEntity p" + where, Long.class);
        params.forEach(count::setParameter);
        long total = count.getSingleResult();
        var served = ServedPage.of(page, size, total);
        var select = entityManager.createQuery("select p from ProcessIndexEntity p" + where
                + " order by p.created desc nulls last", ProcessIndexEntity.class);
        params.forEach(select::setParameter);
        select.setFirstResult((int) served.offset());
        select.setMaxResults(served.size());
        var content = select.getResultList().stream().map(this::toRow).toList();
        return java.util.Optional.of(new ProcessIndexPage(content, total, served.number(), served.size()));
    }

    private ProcessIndexRow toRow(ProcessIndexEntity e) {
        return new ProcessIndexRow(
                e.getProcessId(), e.getBusinessKey(), e.getName(), e.getWorkflowDefinitionId(),
                e.getWorkflowDefinitionVersion(), e.getStatus(), e.getCompletionPercentage(),
                e.getCreated(), e.getStarted(), e.getFinished(), e.getUpdatedAt(), e.getShardId());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteByProcessIds(java.util.Collection<String> processIds) {
        if (processIds.isEmpty()) {
            return 0;
        }
        var present = repository.findAllById(processIds);
        repository.deleteAll(present);
        attributes.deleteAllByProcessIdIn(processIds);
        return present.size();
    }
}
