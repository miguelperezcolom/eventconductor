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
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.OptionsSupplier;
import io.mateu.workflow.analysis.MigrationRules;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.usecases.process.operate.MigrationPlanner;
import io.mateu.workflow.domain.batch.BatchOperationParameters;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * The "Migrate…" dialog of the process detail: move the process onto another version of its definition.
 * Pick the version, adjust the step mapping (pre-filled with the default one, a {@code source=target} line
 * per step, {@code source=} for a step with no counterpart), give the variables the target needs, and
 * <em>Preview</em> — the dry run, changing nothing — before <em>Migrate</em>. See {@code MigrateProcessUseCase}.
 */
@FormLayout(columns = 1)
public class MigrateForm implements OptionsSupplier {

    @Hidden
    String processId;

    @Label("To version")
    @Help("A version of this process's definition; the current one when left empty")
    @Stereotype(FieldStereotype.select)
    String targetVersion;

    @Label("Step mapping")
    @Help("One source=target per line: the step of the version the process runs, and the step it becomes."
            + " source= says it has no counterpart. Steps left out map to the step of the same id.")
    @Stereotype(FieldStereotype.textarea)
    String stepMapping;

    @Label("Variables to set")
    @Help("One name=value per line: what the target version needs and the process does not have")
    @Stereotype(FieldStereotype.textarea)
    String variables;

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in the process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public MigrateForm forProcess(String processId) {
        this.processId = processId;
        this.stepMapping = defaultMapping(processId, null);
        return this;
    }

    /** The default mapping onto {@code version}, one line per step of the process: what the editor starts from. */
    static String defaultMapping(String processId, Integer version) {
        try {
            var process = OperationForms.bean(ProcessRepository.class).findById(processId).orElse(null);
            if (process == null) {
                return "";
            }
            var planned = OperationForms.bean(MigrationPlanner.class).plan(process,
                    OperationForms.bean(StepExecutionRepository.class).findByProcessId(processId), version, Map.of(), Map.of());
            var lines = new StringBuilder();
            planned.plan().steps().stream().filter(step -> step.sourceStepId() != null
                            && step.action() != MigrationRules.Action.CARRY)
                    .forEach(step -> lines.append(step.sourceStepId()).append('=')
                            .append(step.targetStepId() == null ? "" : step.targetStepId()).append('\n'));
            return lines.toString().strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public List<Option> options(String fieldName, HttpRequest httpRequest) {
        if (!"targetVersion".equals(fieldName) || processId == null) {
            return List.of();
        }
        var process = OperationForms.bean(ProcessRepository.class).findById(processId).orElse(null);
        if (process == null) {
            return List.of();
        }
        return OperationForms.bean(ProcessOperationsService.class).migrationVersions(process.getWorkflowDefinitionId())
                .stream()
                .map(v -> new Option(String.valueOf(v), "v" + v
                        + (v == process.getWorkflowDefinitionVersion() ? " (this process's)" : "")))
                .toList();
    }

    @Button
    @Label("Preview")
    public Object preview() {
        try {
            var preview = OperationForms.bean(ProcessOperationsService.class).dryRunMigration(processId, version(),
                    mapping(), BatchOperationParameters.assignments(variables), OperationForms.caller());
            var text = describe(preview);
            return preview.plan().executable() ? Message.success(text) : Message.error(text);
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    @Button
    @Label("Migrate")
    public Object apply() {
        try {
            OperationForms.bean(ProcessOperationsService.class).migrate(processId, version(), mapping(),
                    BatchOperationParameters.assignments(variables), reason, OperationForms.caller());
            return OperationForms.accepted("Migration to " + (version() == null ? "the current version" : "v" + version())
                    + " requested");
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    /** The plan in a few lines: the fate of every step that is not simply remapped, then the problems. */
    static String describe(ProcessOperationsService.MigrationPreview preview) {
        var plan = preview.plan();
        var text = new StringBuilder("v" + preview.fromVersion() + " → v" + preview.toVersion() + ": "
                + (plan.executable() ? "can be migrated." : "refused."));
        plan.steps().stream().filter(step -> step.action() != MigrationRules.Action.REMAP)
                .forEach(step -> text.append("\n• ")
                        .append(step.sourceStepId() == null ? "(new)" : step.sourceStepId())
                        .append(" → ").append(step.targetStepId() == null ? "(none)" : step.targetStepId())
                        .append(": ").append(step.action() == null ? "REFUSED" : step.action().name()));
        plan.problems().forEach(problem -> text.append("\n").append(problem));
        return text.toString();
    }

    private Integer version() {
        return BatchOperationParameters.version(Map.of("v", targetVersion == null ? "" : targetVersion), "v");
    }

    private Map<String, String> mapping() {
        return BatchOperationParameters.assignments(stepMapping);
    }

    @Override
    public String toString() {
        return "Migrate";
    }
}
