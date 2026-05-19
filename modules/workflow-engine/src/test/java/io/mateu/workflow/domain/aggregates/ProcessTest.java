package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessTest {

    @Test
    void shouldCreateProcess() {
        // given
        String processId = "process-1";
        WorkflowDefinition workflowDefinition = new WorkflowDefinition(
                "wd-1", "Test Workflow", 1, "Description", WorkflowDefinitionStatus.ACTIVE,
                null, false, 0, false, List.of()
        );
        String businessKey = "BK-1";
        List<Variable> variables = List.of(new Variable("v1", "val1"));

        // when
        Process process = Process.create(processId, workflowDefinition, businessKey, variables);

        // then
        assertThat(process.getId()).isEqualTo(processId);
        assertThat(process.getName()).isEqualTo(workflowDefinition.name());
        assertThat(process.getWorkflowDefinitionId()).isEqualTo(workflowDefinition.id());
        assertThat(process.getBusinessKey()).isEqualTo(businessKey);
        assertThat(process.getVariables()).hasSize(1);
        assertThat(process.getVariables().get(0).name()).isEqualTo("v1");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.PENDING);
        assertThat(process.getCreated()).isNotNull();
    }

    @Test
    void shouldUpdateVariables() {
        // given
        Process process = Process.builder()
                .variables(new java.util.ArrayList<>(List.of(new Variable("v1", "val1"))))
                .build();

        // when
        process.updateVariables(List.of(new Variable("v1", "new-val"), new Variable("v2", "val2")));

        // then
        assertThat(process.getVariables()).hasSize(2);
        assertThat(process.getVariables()).containsExactlyInAnyOrder(
                new Variable("v1", "new-val"),
                new Variable("v2", "val2")
        );
    }
}
