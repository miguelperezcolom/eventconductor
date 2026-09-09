package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The "new process" form: what to run, under which business key, with which variables.
 *
 * <p>Fields only, no action of its own. It is the creation form of the {@code Processes} Crud, and a
 * Crud draws its own Cancel/Create toolbar for a creation form and fires the reserved {@code create}
 * action when Create is pressed — a button the form supplies itself is never the one that submits.
 * So the actual work of starting the process lives in {@link Processes#create}, which reads these
 * fields back from the submitted component state; putting it here as a {@code @Toolbar} method only
 * ever produced a dead button beside the real one.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@FormLayout(columns = 1)
public class CreateProcessForm {

    @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
    @NotNull
    @Label("Workflow Definition")
    String workflowDefinitionId;

    String businessKey;

    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    List<Variable> variables;

}
