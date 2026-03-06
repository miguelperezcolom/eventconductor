package io.mateu.workflow.domain;

import io.mateu.workflow.application.out.StepExecutionCrudAdapter;
import io.mateu.core.infra.declarative.Entity;
import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Composition;
import io.mateu.uidl.annotations.GeneratedValue;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.KPI;
import io.mateu.uidl.annotations.MasterDetail;

import java.util.List;

public record Process(
        @HiddenInCreate
        @GeneratedValue(UUIDValueGenerator.class)
        String id,
        String workflowDefinitionId,
        int workflowDefinitionVersion,
        String businessKey,
        @Colspan(2)
        @MasterDetail(minHeightWhenDetailVisible = "16rem;")
        List<Variable> variables,
        @KPI
        ProcessStatus status,
        @KPI
        int completionPercentage,
        @Composition(targetClass = StepExecution.class, repositoryClass = StepExecutionCrudAdapter.class, foreignKeyField = "processId")
        List<String> stepExecutions
        ) implements Entity<String> {
}
