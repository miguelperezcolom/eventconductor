package io.mateu.workflow.infra.in.ui.pages.batch;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.domain.batch.BatchOperation;

/** One row of the Batch operations listing: what it does, to which processes, and how far it got. */
public record BatchOperationRow(
        @Label("Id") String id,
        @Label("Operation") String type,
        @Label("Status") Status status,
        @Label("Progress") String progress,
        @Label("Succeeded") long succeeded,
        @Label("Failed") long failed,
        @Label("Skipped") long skipped,
        @Label("Filter") String filter,
        @Label("Created by") String createdBy,
        @Label("Created") String created
) implements Identifiable {

    public static BatchOperationRow of(BatchOperation op) {
        return new BatchOperationRow(op.id(), op.type().name(), BatchOperationFormats.status(op.status()),
                BatchOperationFormats.progress(op), op.succeeded(), op.failed(), op.skipped(), op.filter().describe(),
                op.createdBy() == null ? "—" : op.createdBy(), BatchOperationFormats.moment(op.createdAt()));
    }
}
