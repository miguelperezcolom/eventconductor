package io.mateu.workflow.application.services;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A USER_TASK's assignment and escalation, validated on import: schema, shared rules, templates. */
class UserTaskDefinitionValidationTest {

    private WorkflowDefinitionValidator validator;
    private final YAMLMapper mapper = new YAMLMapper();

    @BeforeEach
    void setUp() throws Exception {
        validator = new WorkflowDefinitionValidator();
        validator.init();
    }

    private void validate(String approveExtra) throws Exception {
        var yaml = """
                id: expenses
                name: Expenses
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: approve
                    type: USER_TASK
                    name: Approve
                    formId: approval
                    preconditionStepId: start
                %s
                  - id: review
                    type: USER_TASK
                    name: Review
                    formId: approval
                    preconditions:
                      - stepId: approve
                        expression: "false"
                  - id: end
                    type: END
                    name: End
                    preconditionStepIds: [approve, review]
                """.formatted(approveExtra.indent(4).stripTrailing());
        validator.validateSource(mapper.readTree(yaml), "inline");
        validator.validate(mapper.readValue(yaml, WorkflowDefinition.class));
    }

    @Test
    void aFullAssignmentAndEscalationIsAccepted() {
        assertThatNoException().isThrownBy(() -> validate("""
                timeout: P10D
                assignment:
                  assignee: "${manager}"
                  candidateUsers: [alice, "${backup}"]
                  candidateGroups: "${groups}"
                  priority: "${amount > 1000 ? 90 : 50}"
                  dueDate: P3D
                  followUpDate: P1D
                escalation:
                  - after: PT48H
                    action: reassign
                    to:
                      candidateGroups: [managers]
                    publishEvent:
                      destination: notifications
                      type: com.acme.task.escalated
                      key: "${businessKey}"
                      payload:
                        task: "${step.id}"
                        level: "${escalation.level}"
                  - after: P5D
                    escalateTo: review"""));
    }

    @Test
    void aMisspelledFieldIsCaughtByTheSchema() {
        assertThatThrownBy(() -> validate("""
                assignment:
                  asignee: bob""")).hasMessageContaining("asignee");
    }

    @Test
    void theSharedRulesApply() {
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P5D
                    escalateTo: nowhere""")).hasMessageContaining("unknown step 'nowhere'");
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P2D
                  - after: P1D""")).hasMessageContaining("order they fire");
    }

    @Test
    void everyTemplateMustParse() {
        assertThatThrownBy(() -> validate("""
                assignment:
                  assignee: "${manager +}\"""")).hasMessageContaining("assignment");
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P1D
                    to:
                      assignee: "${boss +}\"""")).hasMessageContaining("escalation[0].to");
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P1D
                    publishEvent:
                      destination: d
                      type: t
                      payload: {x: "${1 +}"}""")).hasMessageContaining("publishEvent.payload");
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P1D
                    publishEvent:
                      destination: d
                      type: t
                      payloadTemplate: "${1 +}\"""")).hasMessageContaining("publishEvent.payloadTemplate");
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P1D
                    publishEvent:
                      destination: d
                      type: t
                      key: "${1 +}\"""")).hasMessageContaining("publishEvent.key");
        assertThatThrownBy(() -> validate("""
                escalation:
                  - after: P1D
                    publishEvent:
                      destination: d
                      type: t
                      payloadTemplate: x
                      payloadVariables: [a]""")).hasMessageContaining("more than one");
    }
}
