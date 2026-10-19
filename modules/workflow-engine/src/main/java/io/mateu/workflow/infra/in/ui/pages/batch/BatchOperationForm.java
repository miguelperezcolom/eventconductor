package io.mateu.workflow.infra.in.ui.pages.batch;

import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionTagOptionsSupplier;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * The create form of a batch operation: what to do, and to which processes — the Processes page's
 * filters, search attributes included. Fields only, like {@code ScheduleForm}: the
 * {@code BatchOperations} Crud does the work in its {@code create}, and lands on the new batch.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@FormLayout(columns = 2)
public class BatchOperationForm {

    @NotNull
    @Label("Operation")
    @Help("What is done to each process: CANCEL, RETRY (pick an ERROR or CANCELLED process up where it stopped),"
            + " RESTART (run it again from the beginning), PAUSE, RESUME, SET_VARIABLES or RETRY_FROM_STEP (a live"
            + " process, audited with your name and the reason). A process the operation does not apply to is skipped,"
            + " not failed.")
    BatchOperationType type;

    @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
    @Label("Definition")
    String workflowDefinitionId;

    @Lookup(search = WorkflowDefinitionTagOptionsSupplier.class)
    @Label("Tag")
    String tag;

    @Label("Status")
    Set<ProcessStatus> status;

    @Label("Business key")
    @Help("Contains this, case-insensitively.")
    String businessKey;

    @Label("Search attributes")
    @Help("Conditions joined by AND over the attributes the definitions declare: customerId = \"C-9\" AND amount > 100."
            + " Operators =, >, >=, <, <=.")
    String attributes;

    @Label("Created from")
    LocalDateTime createdFrom;

    @Label("Created to")
    LocalDateTime createdTo;

    @Label("Only errors")
    Boolean onlyErrors;

    @Label("Variables to set")
    @Help("SET_VARIABLES: one name=value per line.")
    @io.mateu.uidl.annotations.Stereotype(io.mateu.uidl.data.FieldStereotype.textarea)
    String variablesToSet;

    @Label("Variables to remove")
    @Help("SET_VARIABLES: names, comma-separated.")
    String variablesToRemove;

    @Label("Step")
    @Help("RETRY_FROM_STEP: the step id each process runs again from.")
    String stepId;

    @Label("Reason")
    @Help("Mandatory for SET_VARIABLES and RETRY_FROM_STEP: recorded with your name in each process's audit trail.")
    String reason;

    /** The parameters the form describes. */
    java.util.Map<String, String> parameters() {
        var remove = variablesToRemove == null ? java.util.List.<String>of()
                : java.util.Arrays.stream(variablesToRemove.split(",")).map(String::strip).filter(n -> !n.isEmpty()).toList();
        return io.mateu.workflow.domain.batch.BatchOperationParameters.of(
                io.mateu.workflow.domain.batch.BatchOperationParameters.assignments(variablesToSet), remove, stepId, reason);
    }

    /** The filter the form describes. */
    BatchOperationFilter filter() {
        return new BatchOperationFilter(workflowDefinitionId, tag, status, createdFrom, createdTo, businessKey, null,
                Boolean.TRUE.equals(onlyErrors), attributes);
    }
}
