package io.mateu.workflow.mcp;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FormsMcpTools {

    private final FormRepository formRepository;
    private final FormExecutionRepository formExecutionRepository;

    public record FormSummary(String id, String name, String description, int fieldCount) {}

    public record FormExecutionSummary(
            String id, String formId, String processId,
            String stepExecutionId, String status, String userId, String userGroup) {}

    public record FormExecutionDetail(
            String id, String formId, String processId, String stepExecutionId,
            String status, String userId, String userGroup,
            List<String> variables, List<String> values) {}

    @Tool(description = "List all form definitions available in the forms engine")
    public List<FormSummary> listForms() {
        log.info("Listing forms");
        return formRepository.findAll().stream()
                .map(f -> new FormSummary(
                        f.id(), f.name(), f.description(),
                        f.fields() != null ? f.fields().size() : 0))
                .toList();
    }

    @Tool(description = "List all form executions (pending user tasks) with their status and assignment")
    public List<FormExecutionSummary> listFormExecutions() {
        log.info("Listing form executions");
        return formExecutionRepository.findAll().stream()
                .map(fe -> new FormExecutionSummary(
                        fe.id(), fe.formId(), fe.processId(),
                        fe.stepExecutionId(), fe.status().name(),
                        fe.userId(), fe.userGroup()))
                .toList();
    }

    @Tool(description = "Get full details of a form execution including variable values")
    public FormExecutionDetail getFormExecution(String id) {
        log.info("Getting form execution " + id);
        var fe = formExecutionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("FormExecution not found: " + id));
        var vars = fe.variables() != null
                ? fe.variables().stream().map(v -> v.name() + "=" + v.value()).toList()
                : List.<String>of();
        var vals = fe.values() != null
                ? fe.values().stream().map(v -> v.name() + "=" + v.value()).toList()
                : List.<String>of();
        return new FormExecutionDetail(
                fe.id(), fe.formId(), fe.processId(), fe.stepExecutionId(),
                fe.status().name(), fe.userId(), fe.userGroup(), vars, vals);
    }
}
