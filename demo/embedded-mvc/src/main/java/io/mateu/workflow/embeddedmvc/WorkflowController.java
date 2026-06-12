package io.mateu.workflow.embeddedmvc;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/processes")
@RequiredArgsConstructor
public class WorkflowController {

    private final ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    private final ProcessRepository processRepository;

    record StartProcessRequest(String workflowId, String businessKey, Map<String, String> variables) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Process start(@RequestBody StartProcessRequest request) {
        String businessKey = request.businessKey() != null
                ? request.businessKey()
                : UUID.randomUUID().toString();

        List<Variable> variables = request.variables() == null ? List.of() :
                request.variables().entrySet().stream()
                        .map(e -> new Variable(e.getKey(), e.getValue()))
                        .toList();

        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested(request.workflowId(), businessKey, variables)
        ));

        return processRepository.findByBusinessKey(businessKey).orElseThrow();
    }

    @GetMapping("/{id}")
    public Process get(@PathVariable String id) {
        return processRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
