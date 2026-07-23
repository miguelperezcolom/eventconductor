package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Lifecycle rules: default status on creation, status transitions, and per-status button visibility. */
class WorkflowDefinitionLifecycleTest {

    private WorkflowDefinition def(WorkflowDefinitionStatus status) {
        return new WorkflowDefinition("wd-1", "Test", 1, "desc", status, null, false, 0, false, null, List.of());
    }

    @Test
    void newDefinitionDefaultsToDraft() {
        var wd = new WorkflowDefinition(null, "Test", 0, null, null, null, false, 0, false, null, List.of());
        assertThat(wd.status()).isEqualTo(DRAFT);
    }

    @Test
    void explicitStatusIsKept() {
        assertThat(def(ACTIVE).status()).isEqualTo(ACTIVE);
    }

    @Test
    void withStatusReturnsCopyWithNewStatus() {
        var archived = def(ACTIVE).withStatus(ARCHIVED);
        assertThat(archived.status()).isEqualTo(ARCHIVED);
        assertThat(archived.id()).isEqualTo("wd-1");
        assertThat(archived.name()).isEqualTo("Test");
    }

    @Test
    void editButtonHiddenOnlyWhenActive() {
        assertThat(def(ACTIVE).isHidden("edit", null)).isTrue();
        assertThat(def(DRAFT).isHidden("edit", null)).isFalse();
        assertThat(def(DISABLED).isHidden("edit", null)).isFalse();
        assertThat(def(ARCHIVED).isHidden("edit", null)).isFalse();
    }

    @Test
    void promoteButtonVisibleOnlyForDraftWorkingCopy() {
        // a working copy is a DRAFT that carries the id of the production definition it copies
        var workingCopy = new WorkflowDefinition("wd-1", "Test", 1, "desc", DRAFT, "orig-1", false, 0, false, null, List.of());
        assertThat(workingCopy.isHidden("promoteToProduction", null)).isFalse();
        // a plain DRAFT (not a working copy, draftOfId == null) cannot be promoted
        assertThat(def(DRAFT).isHidden("promoteToProduction", null)).isTrue();
        assertThat(def(ACTIVE).isHidden("promoteToProduction", null)).isTrue();
        assertThat(def(DISABLED).isHidden("promoteToProduction", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("promoteToProduction", null)).isTrue();
    }

    @Test
    void createWorkingCopyButtonVisibleOnlyForActive() {
        assertThat(def(ACTIVE).isHidden("createWorkingCopy", null)).isFalse();
        assertThat(def(DRAFT).isHidden("createWorkingCopy", null)).isTrue();
        assertThat(def(DISABLED).isHidden("createWorkingCopy", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("createWorkingCopy", null)).isTrue();
    }

    @Test
    void disableButtonVisibleOnlyForActive() {
        assertThat(def(ACTIVE).isHidden("disable", null)).isFalse();
        assertThat(def(DRAFT).isHidden("disable", null)).isTrue();
        assertThat(def(DISABLED).isHidden("disable", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("disable", null)).isTrue();
    }

    @Test
    void enableButtonVisibleOnlyForDisabled() {
        assertThat(def(DISABLED).isHidden("enable", null)).isFalse();
        assertThat(def(ACTIVE).isHidden("enable", null)).isTrue();
        assertThat(def(DRAFT).isHidden("enable", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("enable", null)).isTrue();
    }

    @Test
    void reactivateButtonVisibleOnlyForArchived() {
        assertThat(def(ARCHIVED).isHidden("reactivate", null)).isFalse();
        assertThat(def(ACTIVE).isHidden("reactivate", null)).isTrue();
        assertThat(def(DRAFT).isHidden("reactivate", null)).isTrue();
        assertThat(def(DISABLED).isHidden("reactivate", null)).isTrue();
    }

    @Test
    void archiveButtonVisibleOnlyForDraftAndDisabled() {
        assertThat(def(DRAFT).isHidden("archive", null)).isFalse();
        assertThat(def(DISABLED).isHidden("archive", null)).isFalse();
        // an ACTIVE workflow must be disabled before archiving, and ARCHIVED is terminal
        assertThat(def(ACTIVE).isHidden("archive", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("archive", null)).isTrue();
    }

    @Test
    void unknownMemberIsNeverHidden() {
        assertThat(def(ACTIVE).isHidden("somethingElse", null)).isFalse();
    }
}
