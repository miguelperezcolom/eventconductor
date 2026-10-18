package io.mateu.workflow.domain.batch;

import io.mateu.workflow.security.AuthorizationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An operator command applied to every process a filter matches — cancel the 3 000 orders of customer
 * C-9, retry every ERROR process of a definition after an outage — as a thing of its own: created,
 * walked by a background runner a page at a time, throttled, resumable after a restart, cancellable,
 * with its progress and its failures to look at.
 *
 * <p>The walk goes by process id ({@link #cursor}, the last id reached), not by page number: the
 * processes it acts on leave the filter as it goes (a cancelled process is no longer RUNNING), which
 * would shift numbered pages under it; and a runner that restarts carries on after the cursor.
 *
 * @param matched         how many processes the filter matched when the batch was created — an estimate:
 *                        processes can enter or leave it while the batch runs; set to the number visited
 *                        when it completes
 * @param skipped         processes left alone: not in a status the command applies to, gone, or not
 *                        authorized for the caller ({@link #skippedByReason})
 * @param errors          a sample of the failures ({@value #MAX_ERRORS} at most), "processId: message"
 * @param caller          who created it — the flow-authorization identity every process is judged against
 * @param parameters      the arguments of a type that takes them (none of today's do)
 */
public record BatchOperation(
        String id,
        BatchOperationType type,
        BatchOperationFilter filter,
        Map<String, String> parameters,
        BatchOperationStatus status,
        long matched,
        long succeeded,
        long failed,
        long skipped,
        Map<String, Long> skippedByReason,
        String cursor,
        List<String> errors,
        AuthorizationContext caller,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt,
        String failure) {

    /** How many failures are kept to look at. */
    public static final int MAX_ERRORS = 20;

    /** Why a process was skipped. */
    public static final String SKIPPED_NOT_APPLICABLE = "notApplicable";
    public static final String SKIPPED_NOT_AUTHORIZED = "notAuthorized";
    public static final String SKIPPED_GONE = "gone";

    public BatchOperation {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        skippedByReason = skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** A new batch, not yet started. */
    public static BatchOperation create(String id, BatchOperationType type, BatchOperationFilter filter,
                                        Map<String, String> parameters, long matched, AuthorizationContext caller,
                                        LocalDateTime now) {
        return new BatchOperation(id, type, filter, parameters, BatchOperationStatus.CREATED, matched, 0, 0, 0,
                Map.of(), null, List.of(), caller, now, null, null, now, null);
    }

    /** How many processes have been visited. */
    public long processed() {
        return succeeded + failed + skipped;
    }

    /** Progress as a percentage of what the filter matched (100 when it is finished). */
    public int percent() {
        if (status == BatchOperationStatus.COMPLETED) {
            return 100;
        }
        var total = Math.max(matched, processed());
        return total == 0 ? 0 : (int) Math.min(100, processed() * 100 / total);
    }

    /** Who created it, or null for a caller with no identity. */
    public String createdBy() {
        return caller == null ? null : caller.subject();
    }

    public BatchOperation started(LocalDateTime now) {
        return new BatchOperation(id, type, filter, parameters, BatchOperationStatus.RUNNING, matched, succeeded,
                failed, skipped, skippedByReason, cursor, errors, caller, createdAt,
                startedAt == null ? now : startedAt, finishedAt, now, failure);
    }

    /** The progress of one page: the counters it added, the last id it reached. */
    public BatchOperation progressed(String newCursor, long addSucceeded, long addFailed,
                                     Map<String, Long> addSkipped, List<String> newErrors, LocalDateTime now) {
        var reasons = new LinkedHashMap<>(skippedByReason);
        addSkipped.forEach((reason, count) -> reasons.merge(reason, count, Long::sum));
        var addedSkips = addSkipped.values().stream().mapToLong(Long::longValue).sum();
        var sample = new ArrayList<>(errors);
        for (var error : newErrors) {
            if (sample.size() >= MAX_ERRORS) {
                break;
            }
            sample.add(error);
        }
        return new BatchOperation(id, type, filter, parameters, status, matched, succeeded + addSucceeded,
                failed + addFailed, skipped + addedSkips, reasons, newCursor == null ? cursor : newCursor, sample,
                caller, createdAt, startedAt, finishedAt, now, failure);
    }

    /** Finished: every process visited ({@code COMPLETED}), or unable to go on ({@code FAILED}, why). */
    public BatchOperation finished(BatchOperationStatus outcome, String why, LocalDateTime now) {
        var total = outcome == BatchOperationStatus.COMPLETED ? processed() : matched;
        return new BatchOperation(id, type, filter, parameters, outcome, total, succeeded, failed, skipped,
                skippedByReason, cursor, errors, caller, createdAt, startedAt, now, now, why);
    }
}
