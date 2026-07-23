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
    void promoteButtonVisibleOnlyForDraft() {
        assertThat(def(DRAFT).isHidden("promoteToProduction", null)).isFalse();
        assertThat(def(ACTIVE).isHidden("promoteToProduction", null)).isTrue();
        assertThat(def(DISABLED).isHidden("promoteToProduction", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("promoteToProduction", null)).isTrue();
    }

    @Test
    void reactivateButtonVisibleOnlyForDisabled() {
        assertThat(def(DISABLED).isHidden("reactivate", null)).isFalse();
        assertThat(def(ACTIVE).isHidden("reactivate", null)).isTrue();
        assertThat(def(DRAFT).isHidden("reactivate", null)).isTrue();
        assertThat(def(ARCHIVED).isHidden("reactivate", null)).isTrue();
    }

    @Test
    void cancelButtonHiddenOnlyWhenArchived() {
        assertThat(def(ARCHIVED).isHidden("cancel", null)).isTrue();
        assertThat(def(DRAFT).isHidden("cancel", null)).isFalse();
        assertThat(def(ACTIVE).isHidden("cancel", null)).isFalse();
        assertThat(def(DISABLED).isHidden("cancel", null)).isFalse();
    }

    @Test
    void unknownMemberIsNeverHidden() {
        assertThat(def(ACTIVE).isHidden("somethingElse", null)).isFalse();
    }
}
