package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskPage;
import io.mateu.workflow.domain.TaskQuery;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "forms.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class FormExecutionDBRepository implements FormExecutionRepository {

    final FormExecutionEntityRepository formExecutionEntityRepository;

    @Override
    public Optional<FormExecution> findById(String id) {
        return formExecutionEntityRepository.findById(id)
                .map(FormExecutionDBRepository::map);
    }

    static FormExecution map(FormExecutionEntity entity) {
        return new FormExecution(
                entity.getId(),
                entity.getFormId(),
                entity.getProcessId(),
                entity.getStepId(),
                entity.getStepExecutionId(),
                FormExecutionStatus.valueOf(entity.getStatus()),
                entity.getUserId(),
                entity.getUserGroup(),
                listFromJson(entity.getVariables(), Variable.class),
                listFromJson(entity.getValues(), Value.class),
                entity.getCandidateUsers() == null ? List.of() : List.copyOf(entity.getCandidateUsers()),
                entity.getCandidateGroups() == null ? List.of() : List.copyOf(entity.getCandidateGroups()),
                entity.getPriority(),
                entity.getDueDate(),
                entity.getFollowUpDate(),
                entity.getBusinessKey(),
                entity.getCreatedAt(),
                entity.getEscalationLevel(),
                entity.getEscalatedAt(),
                entity.getRestricted()
                );
    }

    @Override
    public String save(FormExecution execution) {
        var entity = new FormExecutionEntity(
                execution.id(),
                execution.formId(),
                execution.processId(),
                execution.stepId(),
                execution.stepExecutionId(),
                toJson(execution.variables()),
                toJson(execution.values()),
                execution.status().name(),
                execution.userId(),
                execution.userGroup()
        );
        entity.setCandidateUsers(new LinkedHashSet<>(execution.candidateUsers()));
        entity.setCandidateGroups(new LinkedHashSet<>(execution.candidateGroups()));
        entity.setPriority(execution.priority());
        entity.setDueDate(execution.dueDate());
        entity.setFollowUpDate(execution.followUpDate());
        entity.setBusinessKey(execution.businessKey());
        entity.setCreatedAt(execution.createdAt());
        entity.setEscalationLevel(execution.escalationLevel());
        entity.setEscalatedAt(execution.escalatedAt());
        entity.setRestricted(execution.restricted());
        formExecutionEntityRepository.save(entity);
        return execution.id();
    }

    @Override
    public List<FormExecution> findAll() {
        return formExecutionEntityRepository.findAll().stream().map(FormExecutionDBRepository::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        formExecutionEntityRepository.deleteAllById(selectedIds);
    }

    @Override
    public List<FormExecution> findByStepExecutionId(String stepExecutionId) {
        return formExecutionEntityRepository.findByStepExecutionId(stepExecutionId).stream()
                .map(FormExecutionDBRepository::map)
                .toList();
    }

    @Override
    public boolean claim(String taskId, String userId) {
        return formExecutionEntityRepository.claimOne(taskId, userId) > 0;
    }

    /**
     * The inbox in SQL: {@code TaskVisibility} translated predicate by predicate — the in-memory
     * repository applies the class itself, and the contract tests hold the two to the same answers —
     * plus the filters and the order of {@link TaskInboxOrder}, nulls placed by CASE so H2 and
     * PostgreSQL sort them alike.
     */
    @Override
    public TaskPage search(TaskQuery query, TaskCaller caller, Collection<String> permittedFormIds) {
        if (permittedFormIds != null && permittedFormIds.isEmpty()) {
            return TaskPage.empty(query);
        }
        var now = TaskInboxOrder.nowUtc();
        Specification<FormExecutionEntity> specification = (root, criteria, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(root.get("status").in(query.statuses().stream().map(Enum::name).toList()));
            if (permittedFormIds != null) {
                predicates.add(root.get("formId").in(permittedFormIds));
            }
            var visible = visible(root, criteria, cb, caller);
            if (visible != null) {
                predicates.add(visible);
            }
            predicates.add(scope(root, criteria, cb, caller, query.scope()));
            if (query.minPriority() != null) {
                predicates.add(cb.greaterThanOrEqualTo(priority(root, cb), query.minPriority()));
            }
            if (Boolean.TRUE.equals(query.overdue())) {
                predicates.add(overdue(root, cb, now));
            } else if (Boolean.FALSE.equals(query.overdue())) {
                predicates.add(cb.not(overdue(root, cb, now)));
            }
            if (query.dueBefore() != null) {
                predicates.add(cb.lessThan(root.get("dueDate"), query.dueBefore()));
            }
            if (query.processId() != null) {
                predicates.add(cb.equal(root.get("processId"), query.processId()));
            }
            if (query.businessKey() != null) {
                predicates.add(cb.equal(root.get("businessKey"), query.businessKey()));
            }
            if (query.formId() != null) {
                predicates.add(cb.equal(root.get("formId"), query.formId()));
            }
            // Spring Data leaves an unsorted page's order to the specification, and drops it from the
            // count query it derives — which is what lets the order use expressions.
            criteria.orderBy(order(root, cb, query.sort()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var page = formExecutionEntityRepository.findAll(specification, PageRequest.of(query.page(), query.size()));
        return new TaskPage(page.getContent().stream().map(FormExecutionDBRepository::map).toList(),
                page.getTotalElements(), query.page(), query.size());
    }

    /** {@code TaskVisibility.canSee}; null when the caller sees everything (an admin). */
    private static Predicate visible(Root<FormExecutionEntity> root, CriteriaQuery<?> criteria, CriteriaBuilder cb,
                                     TaskCaller caller) {
        if (caller.admin()) {
            return null;
        }
        var unassignedAndOffered = cb.and(unassigned(root, cb),
                cb.or(cb.or(cb.isNull(root.get("restricted")), cb.isFalse(root.get("restricted"))),
                        candidate(root, criteria, cb, caller)));
        return caller.known() ? cb.or(holds(root, cb, caller), unassignedAndOffered) : unassignedAndOffered;
    }

    private static Predicate scope(Root<FormExecutionEntity> root, CriteriaQuery<?> criteria, CriteriaBuilder cb,
                                   TaskCaller caller, TaskQuery.Scope scope) {
        return switch (scope) {
            case ALL -> cb.conjunction();
            case MINE -> caller.known() ? holds(root, cb, caller) : cb.disjunction();
            case CANDIDATE -> cb.and(unassigned(root, cb), candidate(root, criteria, cb, caller));
            case GROUPS -> cb.and(unassigned(root, cb), inGroup(root, criteria, cb, caller));
        };
    }

    private static Predicate holds(Root<FormExecutionEntity> root, CriteriaBuilder cb, TaskCaller caller) {
        return cb.equal(root.get("userId"), caller.subject());
    }

    private static Predicate unassigned(Root<FormExecutionEntity> root, CriteriaBuilder cb) {
        return cb.or(cb.isNull(root.get("userId")), cb.equal(root.get("userId"), ""));
    }

    /** {@code TaskVisibility.isCandidate}: named among the candidate users, or in a candidate group. */
    private static Predicate candidate(Root<FormExecutionEntity> root, CriteriaQuery<?> criteria, CriteriaBuilder cb,
                                       TaskCaller caller) {
        var byName = caller.known() ? member(root, criteria, cb, "candidateUsers", List.of(caller.subject())) : cb.disjunction();
        return cb.or(byName, inGroup(root, criteria, cb, caller));
    }

    private static Predicate inGroup(Root<FormExecutionEntity> root, CriteriaQuery<?> criteria, CriteriaBuilder cb,
                                     TaskCaller caller) {
        return caller.groups().isEmpty() ? cb.disjunction()
                : member(root, criteria, cb, "candidateGroups", caller.groups());
    }

    /** Whether one of {@code values} is among the task's {@code collection} — an EXISTS over its table. */
    private static Predicate member(Root<FormExecutionEntity> root, CriteriaQuery<?> criteria, CriteriaBuilder cb,
                                    String collection, Collection<String> values) {
        var subquery = criteria.subquery(Integer.class);
        var task = subquery.from(FormExecutionEntity.class);
        var candidate = task.<FormExecutionEntity, String>join(collection);
        subquery.select(cb.literal(1)).where(cb.equal(task.get("id"), root.get("id")), candidate.in(values));
        return cb.exists(subquery);
    }

    private static Expression<Integer> priority(Root<FormExecutionEntity> root, CriteriaBuilder cb) {
        return cb.coalesce(root.<Integer>get("priority"),
                cb.literal(io.mateu.workflow.dtos.humantasks.HumanTaskAssignment.DEFAULT_PRIORITY));
    }

    private static Predicate overdue(Root<FormExecutionEntity> root, CriteriaBuilder cb, LocalDateTime now) {
        return cb.and(cb.isNotNull(root.get("dueDate")), cb.lessThan(root.get("dueDate"), now),
                root.get("status").in(FormExecutionStatus.PENDING.name(), FormExecutionStatus.ASSIGNED.name()));
    }

    private static List<Order> order(Root<FormExecutionEntity> root, CriteriaBuilder cb, TaskQuery.Sort sort) {
        var dueMissing = cb.selectCase().when(cb.isNull(root.get("dueDate")), 1).otherwise(0);
        var createdMissing = cb.selectCase().when(cb.isNull(root.get("createdAt")), 1).otherwise(0);
        var id = cb.asc(root.get("id"));
        return switch (sort) {
            case PRIORITY -> List.of(cb.desc(priority(root, cb)), cb.asc(dueMissing), cb.asc(root.get("dueDate")),
                    cb.desc(createdMissing), cb.asc(root.get("createdAt")), id);
            case DUE -> List.of(cb.asc(dueMissing), cb.asc(root.get("dueDate")), cb.desc(priority(root, cb)), id);
            case CREATED -> List.of(cb.asc(createdMissing), cb.desc(root.get("createdAt")), id);
        };
    }
}
