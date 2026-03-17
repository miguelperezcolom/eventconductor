package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.dtos.events.TaskExecutionRequested;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.toJson;

@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
@Getter
public final class StepExecution extends AggregateRoot implements Identifiable {

    private String id;
    @HiddenInList
    private String processId;
    @HiddenInList
    private String workflowDefinitionId;
    private String stepId;
    @HiddenInList
    private String stepJson;
    @HiddenInList
    private List<Variable> variables;
    private StepExecutionStatus status;
    private String workerId;


    public static StepExecution create(Step step, String processId) {
        var stepExecution = StepExecution.builder()
                .id(UUID.randomUUID().toString())
                .processId(processId)
                .workflowDefinitionId(step.workflowDefinitionId())
                .stepId(step.id())
                .stepJson(toJson(step))
                .variables(List.of())
                .status(StepExecutionStatus.CREATED)
                .build();
        return stepExecution;
    }

    @Override
    public String id() {
        return id;
    }

    public StepExecution start(List<Variable> variables) {
        this.variables = variables;
        send(new TaskExecutionRequested(id, processId, workflowDefinitionId, stepId, variables.stream()
                .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                .toList()));
        return this;
    }
}
