package io.mateu.workflow.infra.in.ui.pages.process.operate;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.OptionsSupplier;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.security.CallerResolver;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The "Retry from step" dialog of the process detail: pick a step the process has already reached —
 * it and everything after it run again — and say why. See {@code RetryFromStepUseCase}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@FormLayout(columns = 1)
public class RetryFromStepForm implements OptionsSupplier {

    final ProcessOperationsService processOperations;
    final StepExecutionRepository stepExecutionRepository;
    final ObjectProvider<CallerResolver> callerResolver;

    @Hidden
    String processId;

    @NotEmpty
    @Label("Run again from step")
    @Help("This step and every step that depends on it start over; the rest is left as it is")
    @Stereotype(FieldStereotype.select)
    String stepId;

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in the process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public RetryFromStepForm forProcess(String processId) {
        this.processId = processId;
        return this;
    }

    @Override
    public List<Option> options(String fieldName, HttpRequest httpRequest) {
        if (!"stepId".equals(fieldName) || processId == null) {
            return List.of();
        }
        // The steps the process has reached, in the order it reached them — one option per step.
        var reached = new LinkedHashMap<String, StepExecution>();
        stepExecutionRepository.findByProcessId(processId).stream()
                .filter(se -> se.getStartedAt() != null || (se.getStatus() != StepExecutionStatus.CREATED
                        && se.getStatus() != StepExecutionStatus.CANCELLED))
                .sorted(Comparator.comparingLong(StepExecution::getOrder))
                .forEach(se -> reached.putIfAbsent(se.getStepId(), se));
        return reached.values().stream()
                .map(se -> new Option(se.getStepId(), se.getStepId() + " — " + se.getStatus()))
                .toList();
    }

    @Button
    @Label("Retry from this step")
    public Object apply() {
        try {
            processOperations.retryFromStep(processId, stepId, reason, OperationForms.caller(callerResolver));
            return OperationForms.accepted("Retry from step " + stepId + " requested");
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    @Override
    public String toString() {
        return "Retry from step";
    }
}
