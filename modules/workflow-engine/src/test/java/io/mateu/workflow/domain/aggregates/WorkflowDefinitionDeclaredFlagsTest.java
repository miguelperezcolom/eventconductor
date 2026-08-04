package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workflow can live in the repository without being live, and saying so in the definition is a
 * floor rather than a default: the runtime may take a workflow out of service that the file
 * allows, and may not put one into service that the file does not.
 */
class WorkflowDefinitionDeclaredFlagsTest {

    private WorkflowDefinition definition(boolean disabled, boolean archived,
                                          boolean declaredDisabled, boolean declaredArchived) {
        return new WorkflowDefinition("wd-1", "Order", 1, null, false, 0, false, null, 0,
                List.of(), false, disabled, archived, declaredDisabled, declaredArchived);
    }

    @Test
    void isDisabledWhenTheOperatorDisabledIt() {
        assertThat(definition(true, false, false, false).effectivelyDisabled()).isTrue();
    }

    @Test
    void isDisabledWhenTheDefinitionDeclaresIt() {
        assertThat(definition(false, false, true, false).effectivelyDisabled()).isTrue();
    }

    @Test
    void clearingTheRuntimeFlagDoesNotLiftTheDeclaration() {
        // The button an operator presses clears the runtime flag. That must not be enough.
        var declaredOff = definition(true, false, true, false).withDisabled(false);

        assertThat(declaredOff.disabled()).isFalse();
        assertThat(declaredOff.effectivelyDisabled()).isTrue();
    }

    @Test
    void isLiveOnlyWhenNeitherSourceObjects() {
        assertThat(definition(false, false, false, false).effectivelyDisabled()).isFalse();
        assertThat(definition(false, false, false, false).effectivelyArchived()).isFalse();
    }

    @Test
    void archivedFollowsTheSameRule() {
        assertThat(definition(false, false, false, true).effectivelyArchived()).isTrue();
        assertThat(definition(false, true, false, false).effectivelyArchived()).isTrue();
    }

    @Test
    void aReimportKeepsTheRuntimeFlagsAndReplacesTheDeclaration() {
        // The row as it stands: an operator disabled it, and the file said nothing.
        var existing = definition(true, false, false, false);
        // The file, imported again, now declaring itself archived.
        var fromFile = definition(false, false, false, true);

        var reconciled = fromFile.withRuntimeFlagsOf(existing);

        assertThat(reconciled.disabled()).as("the operator's decision survives the import").isTrue();
        assertThat(reconciled.declaredArchived()).as("and the file's is taken as declared").isTrue();
    }
}
