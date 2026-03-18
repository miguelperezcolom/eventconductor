package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.GeneratedValue;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.KPI;
import io.mateu.uidl.annotations.MasterDetail;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

import static io.mateu.core.infra.JsonSerializer.toJson;

@ReadOnly
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@With
public class Process extends AggregateRoot implements Identifiable {
    @HiddenInCreate
    @GeneratedValue(UUIDValueGenerator.class)
    private String id;
    @ReadOnly
    private String workflowDefinitionId;
    @ReadOnly
    private int workflowDefinitionVersion;
    @Hidden
    private String workflowDefinitionJson;
    @ReadOnly
    private String businessKey;
    @Colspan(2)
    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    private List<Variable> variables;
    @KPI
    private ProcessStatus status;
    @KPI
    private int completionPercentage;

    public static Process create(
            String processId,
            WorkflowDefinition workflowDefinition,
            String businessKey,
            List<Variable> variables) {
        var process = Process.builder()
                .id(processId)
                .workflowDefinitionId(workflowDefinition.id())
                .workflowDefinitionJson(toJson(workflowDefinition))
                .workflowDefinitionVersion(workflowDefinition.version())
                .businessKey(businessKey)
                .variables(variables)
                .status(ProcessStatus.PENDING)
                .build();
        process.send(new ProcessCreated(processId, variables.stream()
                .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                .toList()));
        return process;
    }

    @Override
    public String toString() {
        return businessKey;
    }

        @Override
        public String id() {
            return id;
        }

}
