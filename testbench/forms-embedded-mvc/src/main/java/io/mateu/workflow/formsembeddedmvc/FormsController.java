package io.mateu.workflow.formsembeddedmvc;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.Variable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FormsController {

    private final FormRepository formRepository;
    private final FormExecutionRepository formExecutionRepository;
    private final CreateTaskUseCase createTaskUseCase;

    record CreateFormRequest(String name, String description) {}
    record CreateTaskRequest(String formId, String processId, String stepId, Map<String, String> variables) {}

    @PostMapping("/forms")
    @ResponseStatus(HttpStatus.CREATED)
    public Form createForm(@RequestBody CreateFormRequest request) {
        String id = UUID.randomUUID().toString();
        Form form = new Form(id, request.name(), request.description(), List.of());
        formRepository.save(form);
        return formRepository.findById(id).orElseThrow();
    }

    @GetMapping("/forms/{id}")
    public Form getForm(@PathVariable String id) {
        return formRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public FormExecution createTask(@RequestBody CreateTaskRequest request) {
        String stepExecutionId = UUID.randomUUID().toString();

        List<Variable> variables = request.variables() == null ? List.of() :
                request.variables().entrySet().stream()
                        .map(e -> new Variable(e.getKey(), e.getValue()))
                        .toList();

        createTaskUseCase.handle(new CreateTaskCommand(
                stepExecutionId,
                request.processId() != null ? request.processId() : UUID.randomUUID().toString(),
                "workflow-1",
                request.stepId() != null ? request.stepId() : "step-1",
                request.formId(),
                variables
        ));

        return formExecutionRepository.findAll().stream()
                .filter(e -> stepExecutionId.equals(e.stepExecutionId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @GetMapping("/tasks/{id}")
    public FormExecution getTask(@PathVariable String id) {
        return formExecutionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
