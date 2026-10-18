package io.mateu.workflow.infra.in.ui.pages.process.operate;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.OptionsSupplier;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.Variable;
import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The "Complete step" dialog of the process detail: pick one of the steps that can be completed by
 * hand, give what it returns and why — see {@code CompleteStepManuallyUseCase}.
 */
@FormLayout(columns = 1)
public class CompleteStepForm implements OptionsSupplier {

    @Hidden
    String processId;

    @NotEmpty
    @Label("Step")
    @Stereotype(FieldStereotype.select)
    String stepExecutionId;

    @Label("Output variables")
    @Help("Written onto the process, as the step's worker would have")
    @MasterDetail(minHeightWhenDetailVisible = "12rem;")
    List<Variable> variables = new ArrayList<>();

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in the process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public CompleteStepForm forProcess(String processId) {
        this.processId = processId;
        var candidates = candidates();
        if (candidates.size() == 1) {
            this.stepExecutionId = candidates.getFirst().getId();
        }
        return this;
    }

    @Override
    public List<Option> options(String fieldName, HttpRequest httpRequest) {
        if (!"stepExecutionId".equals(fieldName)) {
            return List.of();
        }
        return candidates().stream()
                .map(se -> new Option(se.getId(), se.getStepId() + " — " + se.getStatus()))
                .toList();
    }

    /** The steps of the process that can be completed by hand, in process order. */
    private List<StepExecution> candidates() {
        if (processId == null) {
            return List.of();
        }
        return OperationForms.bean(StepExecutionRepository.class).findByProcessId(processId).stream()
                .filter(se -> ProcessOperationRules.COMPLETABLE.contains(se.getStatus()))
                .sorted(Comparator.comparingLong(StepExecution::getOrder))
                .toList();
    }

    @Button
    @Label("Complete step")
    public Object apply() {
        try {
            OperationForms.bean(ProcessOperationsService.class).completeStep(processId, stepExecutionId, OperationForms.variables(variables), reason,
                    OperationForms.caller());
            return OperationForms.accepted("Manual completion requested");
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    @Override
    public String toString() {
        return "Complete step";
    }
}
