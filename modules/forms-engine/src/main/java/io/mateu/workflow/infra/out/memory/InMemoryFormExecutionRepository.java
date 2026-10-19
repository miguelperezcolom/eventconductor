package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.application.services.TaskVisibility;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskPage;
import io.mateu.workflow.domain.TaskQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "forms.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryFormExecutionRepository implements FormExecutionRepository {

    private final ConcurrentHashMap<String, FormExecution> store = new ConcurrentHashMap<>();

    @Override
    public Optional<FormExecution> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(FormExecution execution) {
        store.put(execution.id(), execution);
        return execution.id();
    }

    @Override
    public List<FormExecution> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }

    @Override
    public TaskPage search(TaskQuery query, TaskCaller caller, Collection<String> permittedFormIds) {
        var now = TaskInboxOrder.nowUtc();
        var matching = store.values().stream()
                .filter(task -> query.statuses().contains(task.status()))
                .filter(task -> permittedFormIds == null || permittedFormIds.contains(task.formId()))
                .filter(task -> TaskVisibility.canSee(task, caller))
                .filter(task -> TaskVisibility.inScope(task, caller, query.scope()))
                .filter(task -> query.minPriority() == null || task.priorityOrDefault() >= query.minPriority())
                .filter(task -> query.overdue() == null || query.overdue() == task.isOverdue(now))
                .filter(task -> query.dueBefore() == null
                        || (task.dueDate() != null && task.dueDate().isBefore(query.dueBefore())))
                .filter(task -> query.processId() == null || query.processId().equals(task.processId()))
                .filter(task -> query.businessKey() == null || query.businessKey().equals(task.businessKey()))
                .filter(task -> query.formId() == null || query.formId().equals(task.formId()))
                .sorted(TaskInboxOrder.comparator(query.sort()))
                .toList();
        var from = Math.min(matching.size(), query.page() * query.size());
        var to = Math.min(matching.size(), from + query.size());
        return new TaskPage(matching.subList(from, to), matching.size(), query.page(), query.size());
    }

    @Override
    public boolean claim(String taskId, String userId) {
        var claimed = new boolean[1];
        store.computeIfPresent(taskId, (id, task) -> {
            if (task.isAssigned() || !task.isOpen()) {
                return task;
            }
            claimed[0] = true;
            return task.withUserId(userId).withStatus(FormExecutionStatus.ASSIGNED);
        });
        return claimed[0];
    }
}
