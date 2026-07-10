package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Route;
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
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Route("/forms/task/:_taskId")
@Service
@RequiredArgsConstructor
@Action(id = "complete", validationRequired = true)
@Action(id = "claim")
@Action(id = "back")
public class Task implements ComponentTreeSupplier, ValidationSupplier, ActionHandler, StateSupplier {

    final FormExecutionRepository formExecutionRepository;
    final FormRepository formRepository;
    final StreamBridge streamBridge;
    final CompleteTaskUseCase completeTaskUseCase;

    String _taskId;

    @Override
    public Component component(HttpRequest httpRequest) {

        var execution = formExecutionRepository.findById(_taskId).orElseThrow();
        var form = formRepository.findById(execution.formId()).orElseThrow();

        List<Component> rows = new ArrayList<>();
        form.fields().forEach(field -> {
            rows.add(FormField.builder()
                                            .id(field.id())
                                            .label(field.label())
                                            .dataType(field.dataType())
                                            .stereotype(field.stereotype())
                                            .required(field.required())
                            .readOnly(FormExecutionStatus.COMPLETED.equals(execution.status()) || execution.userId() == null)
                            .description(field.description())
                                    .build());
        });

        return Form.builder()
                .title("Task " + _taskId)
                .style(StyleConstants.CONTAINER)
                .subtitle(form.name())
                .content(rows)
                .button(Button.builder()
                        .label("Back to list")
                        .actionId("back")
                        .build())
                .button(Button.builder()
                        .label("Claim")
                        .actionId("claim")
                        .disabled(FormExecutionStatus.COMPLETED.equals(execution.status()))
                        .build())
                .button(Button.builder()
                        .label("Complete")
                        .actionId("complete")
                        .disabled(FormExecutionStatus.COMPLETED.equals(execution.status()) || execution.userId() == null)
                        .build())
                .build();
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
            Map<String, Object> state = httpRequest.getComponentState(Map.class);
            completeTaskUseCase.handle(new CompleteTaskCommand(_taskId,
                    state.keySet().stream()
                            .filter(key -> !"_taskId".equals(key))
                            .map(key -> new Value(key, state.get(key).toString())).toList()));

            return UICommand.builder()
                    .type(UICommandType.DispatchEvent)
                    .data(new DispatchEventData(
                            "navigation-requested",
                            NavigationRequestedPayload.builder()
                                    .route("/forms/tasks")
                                    .consumedRoute("")
                                    .baseUrl("/_forms")
                                    .uriPrefix("")
                                    .serverSideType("io.mateu.workflow.infra.in.ui.FormsHome")
                                    .build()
                    ))
                    .build();
        }
        if ("claim".equals(actionId)) {
            var execution = formExecutionRepository.findById(_taskId).orElseThrow();
            execution = execution
                    .withUserId("miguel");
            formExecutionRepository.save(execution);
            streamBridge.send("upstream", new TaskLogEmitted(
                    execution.stepExecutionId(),
                    MessageType.Info,
                    "form " + execution.formId() + " claimed by " + execution.userId()));
        }
        if ("back".equals(actionId)) {
            return UICommand.builder()
                    .type(UICommandType.DispatchEvent)
                    .data(new DispatchEventData(
                            "navigation-requested",
                            NavigationRequestedPayload.builder()
                                    .route("/forms/tasks")
                                    .consumedRoute("")
                                    .baseUrl(httpRequest.getBaseUrl())
                                    .uriPrefix("")
                                    .serverSideType("io.mateu.workflow.infra.in.ui.FormsHome")
                                    .build()
                    ))
                    .build();
        }
        return this;
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
