package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.data.*;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.fluent.Form;
import io.mateu.uidl.interfaces.*;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskCommand;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskUseCase;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.infra.in.ui.FormsRoutes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One task: claim it, fill its form in, complete it. Reached at {@link FormsRoutes#TASK} — the
 * route is registered in {@link FormsRoutes}, not declared here: an {@code @UI} on this class would
 * mount it as an app of its own at the literal path, which is what left the route "Not found."
 * inside {@code /_forms}.
 *
 * <p>A prototype, because the task it is on is a field: a singleton would hand one person's task id
 * to whoever's request came next.
 */
@Slf4j
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Action(id = "complete", validationRequired = true)
@Action(id = "claim")
@Action(id = "back")
public class Task implements ComponentTreeSupplier, ValidationSupplier, ActionHandler, StateSupplier,
        RestSourceSupplier, Hydratable {

    final FormExecutionRepository formExecutionRepository;
    final FormRepository formRepository;
    final StreamBridge streamBridge;
    final CompleteTaskUseCase completeTaskUseCase;
    final io.mateu.workflow.application.services.TaskAuthorization taskAuthorization;

    String _taskId;

    /**
     * The task this page is on, read off the route it was asked for.
     *
     * <p>Mateu fills {@code _taskId} from the route by position, and before 3.0-alpha.385 it lined a
     * registry route ({@code forms/task/:_taskId}, stored without its leading slash) up against the
     * request's ({@code /forms/task/<id>}) one segment out: every task page was asked for the task
     * called "task". Reading the id here makes the page right on either side of that fix.
     */
    @Override
    public void hydrate(HttpRequest httpRequest) {
        var request = httpRequest == null ? null : httpRequest.runActionRq();
        var fromRoute = taskIdOf(request == null ? null : request.route());
        if (fromRoute != null) {
            _taskId = fromRoute;
        }
    }

    /** The task id in a route to this page, or null when the route is not one. */
    static String taskIdOf(String route) {
        if (route == null) {
            return null;
        }
        var path = route.contains("?") ? route.substring(0, route.indexOf('?')) : route;
        var prefix = FormsRoutes.TASK.substring(0, FormsRoutes.TASK.indexOf(":"));
        if (!path.startsWith(prefix)) {
            return null;
        }
        var id = path.substring(prefix.length());
        return id.isBlank() || id.contains("/") ? null : id;
    }

    /**
     * The field's choices as the UI's own options. A field that declares none gets an empty list,
     * which is what a free-input field has always sent.
     */
    private List<Option> options(io.mateu.workflow.domain.Field field) {
        return field.options().stream()
                .map(option -> new Option(option.value(), option.label()))
                .toList();
    }

    /**
     * The field's REST source as the UI's own descriptor, or null when the field has none. The
     * engine hands it over and stops there: the fetch is the renderer's, from the browser or —
     * when the descriptor says {@code proxy} — through the server.
     */
    static RestDataSource optionsSource(io.mateu.workflow.domain.Field field) {
        var source = field.optionsSource();
        // Built by name rather than by position: mateu grew a `ref` component in 3.0-alpha.294,
        // and a positional constructor turns every such addition into a compile error at best and
        // a silently shifted argument at worst.
        return source == null ? null : RestDataSource.builder()
                .url(source.url())
                .method(source.method())
                .headers(source.headers())
                .body(source.body())
                .itemsPath(source.itemsPath())
                .valuePath(source.valuePath())
                .labelPath(source.labelPath())
                .proxy(source.proxy())
                .build();
    }

    /**
     * What this task's fields fetch their choices from, so that a proxy fetch has something to
     * resolve against: mateu reads a proxied source from what the view declared, and a form built
     * at runtime from a stored definition has no annotation for it to read.
     *
     * <p>Read from the repository on every call, which is the whole condition of the proxy not
     * being an open relay: the endpoint comes from the stored definition, never from the request or
     * the component state. The only thing taken from the request is which task this page is on, and
     * that only chooses among definitions the server already holds.
     */
    @Override
    public List<DeclaredRestSource> declaredRestSources() {
        if (_taskId == null || _taskId.isBlank()) {
            return List.of();
        }
        return formExecutionRepository.findById(_taskId)
                .flatMap(execution -> formRepository.findById(execution.formId()))
                .map(form -> form.fields().stream()
                        .filter(field -> field.optionsSource() != null)
                        .map(field -> new DeclaredRestSource(
                                RestSourceKind.OPTIONS, field.id(), optionsSource(field)))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public Component component(HttpRequest httpRequest) {

        var execution = formExecutionRepository.findById(_taskId).orElseThrow();
        var form = formRepository.findById(execution.formId()).orElseThrow();
        var username = JwtExtractor.getUsername(httpRequest).orElse(null);
        var open = isOpen(execution);
        var unassigned = execution.userId() == null || execution.userId().isBlank();
        // Editable by whoever claimed it while it is open — the rule Complete enforces, so a field is
        // never offered to someone whose answer would be refused.
        var mine = open && username != null && username.equals(execution.userId());

        List<Component> rows = new ArrayList<>();
        form.fields().forEach(field -> {
            rows.add(FormField.builder()
                            .id(field.id())
                            .label(field.label())
                            .dataType(field.dataType())
                            .stereotype(field.stereotype())
                            .required(field.required())
                            .readOnly(!mine)
                            .description(field.description())
                            .options(options(field))
                            .optionsSource(optionsSource(field))
                            .build());
        });

        return Form.builder()
                .title(form.name() == null || form.name().isBlank() ? "Task " + _taskId : form.name())
                .style(StyleConstants.CONTAINER)
                .subtitle(subtitle(execution, open, unassigned, mine))
                .content(rows)
                .button(Button.builder()
                        .label("Back to list")
                        .actionId("back")
                        .build())
                .button(Button.builder()
                        .label("Claim")
                        .actionId("claim")
                        .disabled(!open || !unassigned)
                        .build())
                .button(Button.builder()
                        .label("Complete")
                        .actionId("complete")
                        .disabled(!mine)
                        .build())
                .build();
    }

    /** Where the task stands, in the words the person needs to know what to do next. */
    static String subtitle(io.mateu.workflow.domain.FormExecution execution, boolean open, boolean unassigned,
                           boolean mine) {
        var state = FormExecutionStatus.CANCELLED.equals(execution.status()) ? "Cancelled"
                : !open ? "Completed" + (execution.userId() == null ? "" : " by " + execution.userId())
                : unassigned ? "Unassigned — claim it to fill it in"
                : mine ? "Claimed by you"
                : "Claimed by " + execution.userId();
        return state + (execution.processId() == null ? "" : " · process " + execution.processId());
    }

    @Override
    public List<Validation> validations() {
        var execution = formExecutionRepository.findById(_taskId).orElseThrow();
        var form = formRepository.findById(execution.formId()).orElseThrow();
        List<Validation> validations = new ArrayList<>();
        form.fields().stream().filter(Field::required).forEach(field -> {
            validations.add(Validation.builder()
                            .fieldId(field.id())
                    .condition("state['" + field.id() + "']")
                    .message(field.label() + " is required")
                    .build());
        });
        return validations;
    }

    @Override
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        if ("complete".equals(actionId)) {
            var execution = formExecutionRepository.findById(_taskId).orElseThrow();
            var username = JwtExtractor.getUsername(httpRequest).orElse(null);
            // Done by whoever claimed it, and only by them — the button is disabled for anyone else,
            // and this is what makes that more than a hint. The same rule as Tasks v2.
            if (!isOpen(execution) || username == null || !username.equals(execution.userId())) {
                log.info("task {} not completable by {} (status {}, assigned to {})",
                        _taskId, username, execution.status(), execution.userId());
                return this;
            }
            Map<String, Object> state = httpRequest.getComponentState(Map.class);
            completeTaskUseCase.handle(new CompleteTaskCommand(_taskId,
                    state.keySet().stream()
                            .filter(key -> !"_taskId".equals(key))
                            .filter(key -> state.get(key) != null)
                            .map(key -> new Value(key, state.get(key).toString())).toList()));

            return toTasks(httpRequest);
        }
        if ("claim".equals(actionId)) {
            var execution = formExecutionRepository.findById(_taskId).orElseThrow();
            taskAuthorization.refuseIfCallerMayNot("claim",
                    formRepository.findById(execution.formId()).orElse(null), _taskId);
            // The caller, not a name: this used to write "miguel" into every task it claimed, and
            // Complete — enabled for the task's assignee — was then nobody's to press.
            var username = JwtExtractor.getUsername(httpRequest).orElseThrow(
                    () -> new IllegalStateException("Claiming a task needs a signed-in user"));
            if (!isOpen(execution)) {
                return this;
            }
            if (execution.userId() != null && !execution.userId().isBlank()) {
                // Already someone's: claiming does not take work away from a person.
                log.info("task {} could not be claimed by {} (assigned to {})", _taskId, username, execution.userId());
                return this;
            }
            execution = execution.withUserId(username);
            formExecutionRepository.save(execution);
            streamBridge.send("upstream", new TaskLogEmitted(
                    execution.stepExecutionId(),
                    MessageType.Info,
                    "form " + execution.formId() + " claimed by " + execution.userId()));
        }
        if ("back".equals(actionId)) {
            return toTasks(httpRequest);
        }
        return this;
    }

    private static boolean isOpen(io.mateu.workflow.domain.FormExecution execution) {
        return FormExecutionStatus.PENDING.equals(execution.status())
                || FormExecutionStatus.ASSIGNED.equals(execution.status());
    }

    /**
     * To the tasks list, through the front door this page was reached by. Completing used to send
     * the browser to {@code /_forms} whatever console it was in; the list's route answers through
     * either door ({@link FormsRoutes}), so the base URL of this request is the right one.
     */
    private UICommand toTasks(HttpRequest httpRequest) {
        return UICommand.builder()
                .type(UICommandType.DispatchEvent)
                .data(new DispatchEventData(
                        "navigation-requested",
                        NavigationRequestedPayload.builder()
                                .route(FormsRoutes.TASKS)
                                .consumedRoute("")
                                .baseUrl(httpRequest.getBaseUrl())
                                .uriPrefix("")
                                .serverSideType("io.mateu.workflow.infra.in.ui.FormsHome")
                                .build()
                ))
                .build();
    }

    @Override
    public Object state(HttpRequest httpRequest) {
        var execution = formExecutionRepository.findById(_taskId).orElseThrow();
        Map<String, Object> state = new HashMap<>();
        var form = formRepository.findById(execution.formId()).orElseThrow();
        form.fields().stream().filter(field -> FieldDataType.bool.equals(field.dataType())).forEach(field -> state.put(field.id(), false));
        state.put("_taskId", _taskId);
        if (execution.values() != null) {
            execution.values().forEach(value -> state.put(value.name(), value.value()));
        }
        return state;
    }
}
