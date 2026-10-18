package io.mateu.workflow.infra.in.ui.pages.batch;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MainFilter;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;

/** What the Batch operations listing can be narrowed by; the search box matches the id, the filter and who created it. */
public record BatchOperationFilters(
        @MainFilter @Label("Operation") BatchOperationType type,
        @MainFilter @Label("Status") BatchOperationStatus status) {
}
