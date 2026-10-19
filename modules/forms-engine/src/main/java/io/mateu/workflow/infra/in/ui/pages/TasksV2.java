package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.DeclaredRestSource;
import io.mateu.uidl.data.RestSourceKind;
import io.mateu.uidl.data.ButtonColor;
import io.mateu.uidl.data.Details;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FormField;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.TextContainer;
import io.mateu.uidl.data.Validation;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Action;
import io.mateu.uidl.fluent.ActionSupplier;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.ActionHandler;
import io.mateu.uidl.interfaces.ComponentTreeSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.RestSourceSupplier;
import io.mateu.uidl.interfaces.StateSupplier;
import io.mateu.uidl.interfaces.ValidationSupplier;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskCommand;
import io.mateu.workflow.application.services.TaskAuthorization;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskUseCase;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.application.services.TaskActionRefusedException;
import io.mateu.workflow.application.services.TaskCallers;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.application.services.TaskNotFoundException;
import io.mateu.workflow.application.usecases.claimtask.ClaimTaskUseCase;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskQuery;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.mateu.core.infra.JsonSerializer.listFromJson;

/**
 * V2 of the tasks screen: every task is a row whose expandable detail contains the form to
 * complete it, inline — no navigation to a separate page. Completing (or claiming) a task
 * re-renders the whole component, so the row status refreshes in place.
 */
@Service
@Slf4j
@Title("Tasks v2")
@RequiredArgsConstructor
public class TasksV2 implements ComponentTreeSupplier, StateSupplier, ActionHandler, ActionSupplier,
        ValidationSupplier, RestSourceSupplier {

    static final int MAX_TASKS = 50;
    static final String COMPLETE = "complete:";
    static final String CLAIM = "claim:";
    // Joins taskId and fieldId into a page-unique state key, so several tasks can share a form.
    static final String SEPARATOR = "__";

    final FormExecutionEntityRepository repository;
    final FormRepository formRepository;
    final CompleteTaskUseCase completeTaskUseCase;
    final TaskAuthorization taskAuthorization;
    final StreamBridge streamBridge;
    final TaskInbox taskInbox;
    final TaskCallers taskCallers;
    final ClaimTaskUseCase claimTaskUseCase;

    /**
     * The open tasks this person may see, most urgent first (the inbox's rule — what the task names
     * and what its form requires — the same as the Tasks list and the REST API), then the ones they
     * completed, newest first, filling the page.
     */
    private List<FormExecution> tasks(HttpRequest httpRequest) {
        var caller = taskCallers.forUser(JwtExtractor.getUsername(httpRequest).orElse(null));
        var open = taskInbox.search(TaskQuery.open().withPage(0, MAX_TASKS), caller).items();
        var tasks = new ArrayList<>(open);
        if (tasks.size() < MAX_TASKS && caller.known()) {
            tasks.addAll(taskInbox.search(new TaskQuery(TaskQuery.Scope.MINE, List.of(FormExecutionStatus.COMPLETED),
                    null, null, null, null, null, null, TaskQuery.Sort.CREATED, 0, MAX_TASKS - tasks.size()), caller).items());
        }
        return tasks;
    }

    private Optional<Form> form(String formId, Map<String, Optional<Form>> cache) {
        return cache.computeIfAbsent(formId, formRepository::findById);
    }

    /**
     * What the fields of the forms on this page fetch their choices from, under the same composite
     * ids they render with, so a proxy fetch resolves against the stored definitions rather than an
     * annotation this page does not have.
     *
     * <p>Not narrowed to the tasks this user can see, unlike the rendering above: this method has no
     * request to read a user from, and it is not a listing of anything — a declaration is an
     * allow-list entry saying "this field may fetch this url", and the urls are the ones the form
     * definitions already declare, the same for everyone. What matters is what it does NOT read:
     * anything the client sent.
     */
    @Override
    public List<DeclaredRestSource> declaredRestSources() {
        var formCache = new HashMap<String, Optional<Form>>();
        var declarations = new ArrayList<DeclaredRestSource>();
        for (var task : repository.findByStatusIn(
                List.of(FormExecutionStatus.PENDING.name(), FormExecutionStatus.ASSIGNED.name(),
                        FormExecutionStatus.COMPLETED.name()),
                PageRequest.of(0, MAX_TASKS, Sort.by("id"))).getContent()) {
            form(task.getFormId(), formCache).ifPresent(form -> form.fields().stream()
                    .filter(field -> field.optionsSource() != null)
                    .forEach(field -> declarations.add(new DeclaredRestSource(
                            RestSourceKind.OPTIONS,
                            task.getId() + SEPARATOR + field.id(),
                            Task.optionsSource(field)))));
        }
        return declarations;
    }

    @Override
    public Component component(HttpRequest httpRequest) {
        var username = JwtExtractor.getUsername(httpRequest).orElse(null);
        var formCache = new HashMap<String, Optional<Form>>();
        List<Component> rows = new ArrayList<>();
        for (var task : tasks(httpRequest)) {
            form(task.formId(), formCache)
                    .ifPresent(form -> rows.add(taskRow(task, form, username)));
        }
        if (rows.isEmpty()) {
            rows.add(Text.builder()
                    .text("No tasks.")
                    .container(TextContainer.div)
                    .style("color: var(--lumo-secondary-text-color);")
                    .build());
        }
        return VerticalLayout.builder()
                .style("width: 100%;")
                .content(rows)
                .build();
    }

    private Component taskRow(FormExecution task, Form form, String username) {
        var completed = !task.isOpen();
        var unassigned = !task.isAssigned();
        var mine = username != null && username.equals(task.userId());

        var content = new ArrayList<Component>();
        form.fields().forEach(field -> content.add(FormField.builder()
                .id(task.id() + SEPARATOR + field.id())
                .label(field.label())
                .dataType(field.dataType())
                .stereotype(field.stereotype())
                .required(field.required())
                .readOnly(completed || !mine)
                .description(field.description())
                .options(field.options().stream()
                        .map(option -> new Option(option.value(), option.label()))
                        .toList())
                .optionsSource(Task.optionsSource(field))
                .build()));

        var buttons = new ArrayList<Component>();
        if (!completed && unassigned) {
            buttons.add(Button.builder()
                    .label("Claim")
                    .actionId(CLAIM + task.id())
                    .build());
        }
        if (!completed) {
            buttons.add(Button.builder()
                    .label("Complete")
                    .color(ButtonColor.success)
                    .disabled(!mine)
                    .actionId(COMPLETE + task.id())
                    .build());
        }
        if (!buttons.isEmpty()) {
            content.add(HorizontalLayout.builder()
                    .spacing(true)
                    .content(buttons)
                    .build());
        }

        return Details.builder()
                .summary(summary(task, form, completed, unassigned))
                .content(VerticalLayout.builder()
                        .style("width: 100%;")
                        .content(content)
                        .build())
                .opened(false)
                .style("width: 100%;")
                .build();
    }

    private Component summary(FormExecution task, Form form, boolean completed, boolean unassigned) {
        var parts = new ArrayList<Component>(List.of(
                Text.builder()
                        .text(form.name())
                        .style("font-weight: 600;")
                        .build(),
                Text.builder()
                        .text(task.businessKey() != null ? task.businessKey() : task.processId())
                        .style("color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s);")
                        .build(),
                Text.builder()
                        .text(unassigned ? "Unassigned" : task.userId())
                        .style("color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s);")
                        .build(),
                Text.builder()
                        .text("P" + task.priorityOrDefault())
                        .style("font-size: var(--lumo-font-size-s);")
                        .build()));
        if (task.dueDate() != null) {
            parts.add(Text.builder()
                    .text("due " + task.dueDate().toString().replace('T', ' ') + " UTC")
                    .style("color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s);")
                    .build());
        }
        if (task.isOverdue(TaskInboxOrder.nowUtc())) {
            parts.add(Text.builder()
                    .text("Overdue")
                    .style("font-weight: 600; font-size: var(--lumo-font-size-s); color: var(--lumo-error-text-color);")
                    .build());
        }
        if (task.escalations() > 0) {
            parts.add(Text.builder()
                    .text("Escalated")
                    .style("font-weight: 600; font-size: var(--lumo-font-size-s); color: var(--lumo-warning-text-color, #b26b00);")
                    .build());
        }
        parts.add(Text.builder()
                .text(completed ? "Completed" : "Pending")
                .style("font-weight: 600; font-size: var(--lumo-font-size-s); color: var(--lumo-"
                        + (completed ? "success" : "primary") + "-text-color);")
                .build());
        return HorizontalLayout.builder()
                .spacing(true)
                .style("align-items: baseline; flex-wrap: wrap; gap: 0.25rem 1.5rem;")
                .content(parts)
                .build();
    }

    @Override
    public Object state(HttpRequest httpRequest) {
        Map<String, Object> state = new HashMap<>();
        var formCache = new HashMap<String, Optional<Form>>();
        for (var task : tasks(httpRequest)) {
            var prefix = task.id() + SEPARATOR;
            form(task.formId(), formCache).ifPresent(form -> form.fields().stream()
                    .filter(field -> FieldDataType.bool.equals(field.dataType()))
                    .forEach(field -> state.put(prefix + field.id(), false)));
            if (task.values() != null) {
                task.values().forEach(value -> state.put(prefix + value.name(), value.value()));
            }
        }
        return state;
    }

    @Override
    public List<Action> actions(HttpRequest httpRequest) {
        var actions = new ArrayList<Action>();
        var formCache = new HashMap<String, Optional<Form>>();
        for (var task : tasks(httpRequest)) {
            if (!task.isOpen()) {
                continue;
            }
            var fieldsToValidate = form(task.formId(), formCache).stream()
                    .flatMap(form -> form.fields().stream())
                    .map(field -> task.id() + SEPARATOR + field.id())
                    .collect(Collectors.joining(","));
            actions.add(Action.builder()
                    .id(COMPLETE + task.id())
                    .validationRequired(true)
                    .fieldsToValidate(fieldsToValidate)
                    .build());
            actions.add(Action.builder().id(CLAIM + task.id()).build());
        }
        return actions;
    }

    @Override
    public List<Validation> validations() {
        var validations = new ArrayList<Validation>();
        var formCache = new HashMap<String, Optional<Form>>();
        for (var task : repository.findByStatusIn(
                List.of(FormExecutionStatus.PENDING.name(), FormExecutionStatus.ASSIGNED.name()),
                PageRequest.of(0, 1000, Sort.by("id"))).getContent()) {
            form(task.getFormId(), formCache).ifPresent(form -> form.fields().stream()
                    .filter(Field::required)
                    .forEach(field -> {
                        var fieldId = task.getId() + SEPARATOR + field.id();
                        validations.add(Validation.builder()
                                .fieldId(fieldId)
                                .condition("state['" + fieldId + "']")
                                .message(field.label() + " is required")
                                .build());
                    }));
        }
        return validations;
    }

    @Override
    @Transactional
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        if (actionId.startsWith(CLAIM)) {
            claim(actionId.substring(CLAIM.length()), httpRequest);
        }
        if (actionId.startsWith(COMPLETE)) {
            complete(actionId.substring(COMPLETE.length()), httpRequest);
        }
        return this;
    }

    private void claim(String taskId, HttpRequest httpRequest) {
        var username = JwtExtractor.getUsername(httpRequest).orElseThrow();
        // Claiming is taking the work, so it is gated like doing it — by what the task names and by
        // what its form requires (ClaimTaskUseCase). The listing above already hides what this person
        // may not touch; this is what makes that hiding irrelevant to security.
        try {
            claimTaskUseCase.handle(taskId, taskCallers.forUser(username));
        } catch (TaskActionRefusedException | TaskNotFoundException e) {
            log.info("task {} could not be claimed by {}: {}", taskId, username, e.getMessage());
        }
    }

    private void complete(String taskId, HttpRequest httpRequest) {
        var username = JwtExtractor.getUsername(httpRequest).orElseThrow();
        Map<String, Object> state = httpRequest.getComponentState(Map.class);
        var prefix = taskId + SEPARATOR;
        var values = state.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix) && entry.getValue() != null)
                .map(entry -> new io.mateu.workflow.domain.Value(
                        entry.getKey().substring(prefix.length()),
                        entry.getValue().toString()))
                .toList();
        try {
            completeTaskUseCase.handle(new CompleteTaskCommand(taskId, values), taskCallers.forUser(username));
        } catch (TaskActionRefusedException | TaskNotFoundException e) {
            log.info("task {} not completable by {}: {}", taskId, username, e.getMessage());
        }
    }

}
