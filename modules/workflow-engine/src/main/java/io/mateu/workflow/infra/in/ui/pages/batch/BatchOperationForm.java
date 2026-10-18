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
            + " RESTART (run it again from the beginning), PAUSE or RESUME. A process the operation does not apply to"
            + " is skipped, not failed.")
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

    /** The filter the form describes. */
    BatchOperationFilter filter() {
        return new BatchOperationFilter(workflowDefinitionId, tag, status, createdFrom, createdTo, businessKey, null,
                Boolean.TRUE.equals(onlyErrors), attributes);
    }
}
