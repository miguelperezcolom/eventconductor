package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.workflow.application.out.StepExecutionCrudAdapter;
import io.mateu.core.infra.declarative.Entity;
import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Composition;
import io.mateu.uidl.annotations.GeneratedValue;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.KPI;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.workflow.domain.events.ProcessCreated;
import io.mateu.workflow.domain.shared.AggregateRoot;
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
public class Process extends AggregateRoot implements Entity<String> {
    @HiddenInCreate
    @GeneratedValue(UUIDValueGenerator.class)
    private String id;
    private String workflowDefinitionId;
    private int workflowDefinitionVersion;
    @Hidden
    private String workflowDefinitionJson;
    private String businessKey;
    @Colspan(2)
    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    private List<Variable> variables;
    @KPI
    private ProcessStatus status;
    @KPI
    private int completionPercentage;
    @Composition(targetClass = StepExecution.class, repositoryClass = StepExecutionCrudAdapter.class, foreignKeyField = "processId")
    private List<String> stepExecutions;

    public static Process create(
            String processId,
            WorkflowDefinition workflowDefinition,
            String businessKey,
            List<Variable> variables,
            List<String> stepExecutions) {
        var process = Process.builder()
                .id(processId)
                .workflowDefinitionId(workflowDefinition.id())
                .workflowDefinitionJson(toJson(workflowDefinition))
                .workflowDefinitionVersion(workflowDefinition.version())
                .businessKey(businessKey)
                .variables(variables)
                .status(ProcessStatus.PENDING)
                .stepExecutions(stepExecutions)
                .build();
        process.send(new ProcessCreated(processId));
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
