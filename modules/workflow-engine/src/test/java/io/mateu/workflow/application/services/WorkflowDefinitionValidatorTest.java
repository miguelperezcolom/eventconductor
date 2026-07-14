package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionValidatorTest {

    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new WorkflowDefinitionValidator(new ObjectMapper());
        validator.init();
    }

    private Step actionStep(String id) {
        return new Step(id, null, StepType.ACTION, "Step " + id, null, null, null, false, "my-topic", null, null, null, 0, 0, false, null);
    }

    @Test
    void validDefinitionPassesValidation() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",
                WorkflowDefinitionStatus.ACTIVE, null, false, 0, false, List.of(actionStep("s1")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void definitionWithSelfPreconditionFailsInvariantCheck() {
        Step selfPrecondition = new Step("s1", null, StepType.ACTION, "Step", null, "s1", null, false, "topic", null, null, null, 0, 0, false, null);
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc",
                WorkflowDefinitionStatus.ACTIVE, null, false, 0, false, List.of(selfPrecondition));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void definitionWithMissingNameFailsSchemaValidation() {
        var wd = new WorkflowDefinition("wd-1", null, 1, "desc",
                WorkflowDefinitionStatus.ACTIVE, null, false, 0, false, List.of(actionStep("s1")));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class);
    }
}
