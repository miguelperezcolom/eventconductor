package io.mateu.workflow.infra.in.ui.pages.process.operate;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.OptionsSupplier;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationParameters;
import io.mateu.workflow.domain.batch.BatchOperationType;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "Migrate instances…" dialog of a definition's page: move every live (RUNNING, PAUSED or ERROR) process
 * on one version onto another — a MIGRATE batch operation, each process planned and refused on its own, the
 * same mapping for all. Follow it on the Batch operations page.
 */
@FormLayout(columns = 1)
public class MigrateInstancesForm implements OptionsSupplier {

    @Hidden
    String workflowDefinitionId;

    @Label("Instances of version")
    @Help("Only the processes on this version; all versions when left empty")
    @Stereotype(FieldStereotype.select)
    String fromVersion;

    @Label("To version")
    @Help("The current one when left empty")
    @Stereotype(FieldStereotype.select)
    String targetVersion;

    @Label("Step mapping")
    @Help("One source=target per line, the same for every process; source= says no counterpart. Steps left out map"
            + " to the step of the same id.")
    @Stereotype(FieldStereotype.textarea)
    String stepMapping;

    @Label("Variables to set")
    @Help("One name=value per line")
    @Stereotype(FieldStereotype.textarea)
    String variables;

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in each process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public MigrateInstancesForm forDefinition(String workflowDefinitionId) {
        this.workflowDefinitionId = workflowDefinitionId;
        return this;
    }

    @Override
    public List<Option> options(String fieldName, HttpRequest httpRequest) {
        if ((!"fromVersion".equals(fieldName) && !"targetVersion".equals(fieldName)) || workflowDefinitionId == null) {
            return List.of();
        }
        return OperationForms.bean(ProcessOperationsService.class).migrationVersions(workflowDefinitionId).stream()
                .map(v -> new Option(String.valueOf(v), "v" + v)).toList();
    }

    @Button
    @Label("Migrate instances")
    public Object apply() {
        try {
            var filter = new BatchOperationFilter(workflowDefinitionId, null,
                    Set.of(ProcessStatus.RUNNING, ProcessStatus.PAUSED, ProcessStatus.ERROR), null, null, null, null,
                    false, null);
            var parameters = BatchOperationParameters.migrate(version(targetVersion), version(fromVersion),
                    BatchOperationParameters.assignments(stepMapping), BatchOperationParameters.assignments(variables),
                    reason);
            var batch = OperationForms.bean(BatchOperationService.class).create(BatchOperationType.MIGRATE, filter,
                    parameters, OperationForms.caller());
            return List.of(Message.success("Batch operation " + batch.id() + " migrates the " + batch.matched()
                            + " live process(es) of " + workflowDefinitionId + " in the background; follow it on the Batch"
                            + " operations page."),
                    UICommand.closeModal(OperationForms.OPERATED_EVENT));
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    private static Integer version(String value) {
        return BatchOperationParameters.version(Map.of("v", value == null ? "" : value), "v");
    }

    @Override
    public String toString() {
        return "Migrate instances";
    }
}
