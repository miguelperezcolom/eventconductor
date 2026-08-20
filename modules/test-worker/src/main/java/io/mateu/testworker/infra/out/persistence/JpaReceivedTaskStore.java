package io.mateu.testworker.infra.out.persistence;

import io.mateu.uidl.data.Direction;
import io.mateu.uidl.data.FilterCriterion;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.workflow.dtos.Variable;
import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.ScenarioSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Received tasks in the database — the history the UI browses and builds overrides from. */
@Service
@ConditionalOnProperty(name = "worker.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaReceivedTaskStore implements ReceivedTaskStore {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "receivedAt");

    private final ReceivedTaskEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public int previousDeliveriesOf(String taskExecutionId) {
        return repository.findById(taskExecutionId)
                .map(ReceivedTaskEntity::getAttempt)
                .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceivedTask> findById(String id) {
        return repository.findById(id).map(this::map);
    }

    @Override
    @Transactional
    public String save(ReceivedTask row) {
        var entity = repository.findById(row.id()).orElseGet(ReceivedTaskEntity::new);
        entity.setId(row.id());
        entity.setProcessId(row.processId());
        entity.setWorkflowDefinitionId(row.workflowDefinitionId());
        entity.setStepId(row.stepId());
        entity.setTaskId(row.taskId());
        entity.setReceivedAt(row.receivedAt());
        entity.setAttempt(row.attempt());
        entity.setSource(Json.nameOf(row.source()));
        entity.setMatchedBy(row.matchedBy());
        entity.setOutcome(Json.nameOf(row.outcome()));
        entity.setDurationMs(row.durationMs());
        entity.setRepliedAt(row.repliedAt());
        entity.setNote(row.note());
        entity.setRequestVariablesJson(Json.toJson(row.requestVariables()));
        entity.setScenarioJson(row.scenarioJson());
        repository.save(entity);
        return row.id();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceivedTask> findAll() {
        return repository.findAll(NEWEST_FIRST).stream().map(this::map).toList();
    }

    /**
     * The paged hook the Crud calls to render the listing. Overridden because the default one is
     * {@link #findAll()} paged in memory, and a worker that has been running a while has tens of
     * thousands of rows here — the page took seconds to show ten of them.
     *
     * <p>Text search and paging go to SQL. The filter object and the column criteria do not: their
     * semantics are mateu's, they live in private reflection inside {@code CrudStore}, and a copy
     * of them here would be a copy that silently drifts. When either is in play this falls back to
     * the default, which is exactly today's behaviour — slower, but nobody's first click.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ReceivedTask> find(String searchText, ReceivedTask filters,
                                   List<FilterCriterion> criteria, Pageable pageable) {
        if (hasActiveFilter(filters) || (criteria != null && !criteria.isEmpty())) {
            return ReceivedTaskStore.super.find(searchText, filters, criteria, pageable);
        }
        var size = pageable != null && pageable.size() > 0 ? pageable.size() : Integer.MAX_VALUE;
        var pageNumber = pageable != null ? Math.max(0, pageable.page()) : 0;
        var found = repository.findAll(matching(searchText),
                PageRequest.of(pageNumber, size, sortOf(pageable)));
        return new Page<>("", size, pageNumber, found.getTotalElements(),
                found.getContent().stream().map(this::map).toList());
    }

    /**
     * The same match {@code CrudStore} makes in memory: the needle is split on whitespace and every
     * token has to appear, case-insensitively, in what the row's {@code toString()} produces —
     * which for a {@link ReceivedTask} is its task id and process id.
     */
    private static Specification<ReceivedTaskEntity> matching(String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return null;
        }
        var tokens = searchText.trim().split("\\s+");
        return (root, query, cb) -> {
            var haystack = cb.lower(cb.concat(
                    cb.concat(cb.coalesce(root.get("taskId"), ""), " \u00b7 "),
                    cb.coalesce(root.get("processId"), "")));
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            for (var token : tokens) {
                predicates.add(cb.like(haystack, "%" + token.toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    /** The sort the grid asked for, or the newest-first this page is documented to show. */
    private static Sort sortOf(Pageable pageable) {
        if (pageable == null || pageable.sort() == null || pageable.sort().isEmpty()) {
            return NEWEST_FIRST;
        }
        var orders = pageable.sort().stream()
                .map(sort -> Direction.descending.equals(sort.direction())
                        ? Sort.Order.desc(sort.field())
                        : Sort.Order.asc(sort.field()))
                .toList();
        return Sort.by(orders);
    }

    /**
     * Whether the filter form carries anything to filter by. Deliberately more cautious than the
     * emptiness rule {@code CrudStore} applies: a field this reads as set but mateu would have
     * ignored only costs the in-memory path, whereas the other way round would drop a filter.
     */
    private static boolean hasActiveFilter(ReceivedTask filters) {
        if (filters == null) {
            return false;
        }
        for (var field : ReceivedTask.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                var value = field.get(filters);
                if (value == null
                        || value instanceof String text && text.isBlank()
                        || value instanceof Collection<?> collection && collection.isEmpty()) {
                    continue;
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Unreadable field: assume it filters, and take the slow, correct path.
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional
    public void deleteAllById(List<String> ids) {
        repository.deleteAllById(ids);
    }

    private ReceivedTask map(ReceivedTaskEntity entity) {
        return new ReceivedTask(
                entity.getId(), entity.getProcessId(), entity.getWorkflowDefinitionId(),
                entity.getStepId(), entity.getTaskId(), entity.getReceivedAt(),
                entity.getAttempt(), Json.enumOf(ScenarioSource.class, entity.getSource()),
                entity.getMatchedBy(), Json.enumOf(Outcome.class, entity.getOutcome()),
                entity.getDurationMs(), entity.getRepliedAt(), entity.getNote(),
                Json.listFrom(entity.getRequestVariablesJson(), Variable.class),
                entity.getScenarioJson());
    }
}
