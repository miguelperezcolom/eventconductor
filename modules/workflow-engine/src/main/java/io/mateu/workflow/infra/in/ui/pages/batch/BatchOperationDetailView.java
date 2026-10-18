package io.mateu.workflow.infra.in.ui.pages.batch;

import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * A batch operation's own page: what it does, to which processes, how far it got — a progress bar and the
 * counters — why it skipped what it skipped, and a sample of what failed. Cancel while it runs; Refresh to
 * read the progress again.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class BatchOperationDetailView implements VisibilitySupplier {

    final BatchOperationService batches;

    @Hidden
    String id;

    @Hidden
    boolean active;

    /** Promoted to a header badge. */
    Status status;

    @Label("Operation")
    String type;

    @Label("Filter")
    String filter;

    @Label("Progress")
    String progress;

    // Text, so a zero shows as 0 rather than as an empty field.
    @Label("Succeeded")
    String succeeded;

    @Label("Failed")
    String failed;

    @Label("Skipped")
    String skipped;

    @Label("Created by")
    String createdBy;

    @Label("Created")
    String created;

    @Label("Started")
    String started;

    @Label("Finished")
    String finished;

    /** The progress bar and the error sample, as one element across the width. */
    @Label("")
    @Colspan(2)
    Element details;

    public BatchOperationDetailView load(String batchId) {
        return fill(batches.get(batchId).orElseThrow(() -> new BatchOperationService.BatchOperationNotFoundException(batchId)));
    }

    BatchOperationDetailView fill(BatchOperation op) {
        this.id = op.id();
        this.active = op.status().isActive();
        this.status = BatchOperationFormats.status(op.status());
        this.type = op.type().name();
        this.filter = op.filter().describe();
        this.progress = BatchOperationFormats.progress(op);
        this.succeeded = String.valueOf(op.succeeded());
        this.failed = String.valueOf(op.failed());
        this.skipped = op.skipped() + (op.skippedByReason().isEmpty() ? "" : " (" + op.skippedByReason().entrySet().stream()
                .map(e -> reason(e.getKey()) + ": " + e.getValue()).collect(Collectors.joining(", ")) + ")");
        this.createdBy = op.createdBy() == null ? "—" : op.createdBy();
        this.created = BatchOperationFormats.moment(op.createdAt());
        this.started = BatchOperationFormats.moment(op.startedAt());
        this.finished = BatchOperationFormats.moment(op.finishedAt());
        var total = Math.max(1, Math.max(op.matched(), op.processed()));
        var html = new StringBuilder()
                .append("<progress style=\"width: 100%; height: 1.2em;\" max=\"").append(total).append("\" value=\"")
                .append(op.status() == BatchOperationStatus.COMPLETED ? total : op.processed()).append("\"></progress>")
                .append("<p>").append(op.percent()).append("% — ").append(op.processed()).append(" of ")
                .append(Math.max(op.matched(), op.processed())).append(" processes</p>");
        if (op.failure() != null) {
            html.append("<h4>Why it stopped</h4><p>").append(BatchOperationFormats.escape(op.failure())).append("</p>");
        }
        html.append("<h4>Errors</h4>");
        if (op.errors().isEmpty()) {
            html.append("<p>—</p>");
        } else {
            html.append(op.errors().stream().map(e -> "<li>" + BatchOperationFormats.escape(e) + "</li>")
                    .collect(Collectors.joining("", "<ul>", "</ul>")));
            if (op.failed() > op.errors().size()) {
                html.append("<p>(the first ").append(op.errors().size()).append(" of ").append(op.failed()).append(")</p>");
            }
        }
        this.details = Element.builder()
                .attributes(Map.of())
                .on(Map.of())
                .name("div")
                .html(true)
                .content(html.toString())
                .style("width: 100%;")
                .build();
        return this;
    }

    private static String reason(String key) {
        return switch (key) {
            case BatchOperation.SKIPPED_NOT_APPLICABLE -> "status does not apply";
            case BatchOperation.SKIPPED_NOT_AUTHORIZED -> "not authorized";
            case BatchOperation.SKIPPED_GONE -> "gone";
            default -> key;
        };
    }

    @Toolbar
    @Label("Refresh")
    public UICommand refresh(HttpRequest httpRequest) {
        return UICommand.navigateTo(id);
    }

    @Toolbar
    @Label("Cancel batch")
    @Action(confirmationRequired = true, confirmationTitle = "Cancel batch operation",
            confirmationMessage = "Stop this batch? No further process is touched; what was done stays done.",
            confirmationText = "Cancel batch", confirmationDenialText = "Keep running")
    public UICommand cancelBatch(HttpRequest httpRequest) {
        batches.cancel(id);
        return UICommand.navigateTo(id);
    }

    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "new", "delete", "edit" -> true;
            case "cancelBatch" -> !active;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return id != null ? "Batch operation " + id : "Batch operation";
    }
}
