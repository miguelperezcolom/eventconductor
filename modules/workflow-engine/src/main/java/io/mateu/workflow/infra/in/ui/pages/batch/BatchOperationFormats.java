package io.mateu.workflow.infra.in.ui.pages.batch;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationStatus;

import java.time.format.DateTimeFormatter;

/** How the batch operation pages write a batch: its status badge, its progress, its dates. */
final class BatchOperationFormats {

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BatchOperationFormats() {
    }

    static Status status(BatchOperationStatus status) {
        var type = switch (status) {
            case CREATED -> StatusType.INFO;
            case RUNNING -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case FAILED, CANCELLED -> StatusType.DANGER;
        };
        var name = status.name();
        return new Status(type, name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT));
    }

    static String progress(BatchOperation op) {
        var total = Math.max(op.matched(), op.processed());
        return op.processed() + " / " + total + " (" + op.percent() + "%)";
    }

    static String moment(java.time.LocalDateTime value) {
        return value == null ? "—" : value.format(DATE_TIME);
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
