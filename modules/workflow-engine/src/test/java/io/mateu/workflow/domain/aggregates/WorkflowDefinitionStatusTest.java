package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workflow can live in the repository without being live, and saying so in the definition is a
 * floor rather than a default: the runtime may take a workflow out of service that the file
 * allows, and may not put one into service that the file does not.
 */
class WorkflowDefinitionStatusTest {

    private WorkflowDefinition definition(WorkflowStatus declared, WorkflowStatus runtime) {
        return new WorkflowDefinition("wd-1", "Order", 1, null, false, 0, false, null, 0,
                List.of(), false, declared, runtime);
    }

    @Test
    void isDisabledWhenTheOperatorDisabledIt() {
        assertThat(definition(WorkflowStatus.ACTIVE, WorkflowStatus.DISABLED).status())
                .isEqualTo(WorkflowStatus.DISABLED);
    }

    @Test
    void isDisabledWhenTheDefinitionDeclaresIt() {
        assertThat(definition(WorkflowStatus.DISABLED, WorkflowStatus.ACTIVE).status())
                .isEqualTo(WorkflowStatus.DISABLED);
    }

    @Test
    void clearingTheRuntimeFlagDoesNotLiftTheDeclaration() {
        // The button an operator presses clears the runtime flag. That must not be enough.
        var declaredOff = definition(WorkflowStatus.DISABLED, WorkflowStatus.DISABLED)
                .withRuntimeStatus(WorkflowStatus.ACTIVE);

        assertThat(declaredOff.runtimeStatus()).isEqualTo(WorkflowStatus.ACTIVE);
        assertThat(declaredOff.status()).isEqualTo(WorkflowStatus.DISABLED);
    }

    @Test
    void isLiveOnlyWhenNeitherSourceObjects() {
        assertThat(definition(WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE).status())
                .isEqualTo(WorkflowStatus.ACTIVE);
    }

    @Test
    void archivedFollowsTheSameRule() {
        assertThat(definition(WorkflowStatus.ARCHIVED, WorkflowStatus.ACTIVE).status())
                .isEqualTo(WorkflowStatus.ARCHIVED);
        assertThat(definition(WorkflowStatus.ACTIVE, WorkflowStatus.ARCHIVED).status())
                .isEqualTo(WorkflowStatus.ARCHIVED);
        // the stricter of the two wins, whichever side it comes from
        assertThat(definition(WorkflowStatus.DISABLED, WorkflowStatus.ARCHIVED).status())
                .isEqualTo(WorkflowStatus.ARCHIVED);
    }

    @Test
    void aReimportKeepsTheRuntimeFlagsAndReplacesTheDeclaration() {
        // The row as it stands: an operator disabled it, and the file said nothing.
        var existing = definition(WorkflowStatus.ACTIVE, WorkflowStatus.DISABLED);
        // The file, imported again, now declaring itself archived.
        var fromFile = definition(WorkflowStatus.ARCHIVED, WorkflowStatus.ACTIVE);

        var reconciled = fromFile.withRuntimeStateOf(existing);

        assertThat(reconciled.runtimeStatus()).as("the operator's decision survives the import")
                .isEqualTo(WorkflowStatus.DISABLED);
        assertThat(reconciled.declaredStatus()).as("and the file's is taken as declared")
                .isEqualTo(WorkflowStatus.ARCHIVED);
    }
}
