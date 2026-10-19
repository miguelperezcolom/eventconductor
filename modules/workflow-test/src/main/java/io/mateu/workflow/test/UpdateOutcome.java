package io.mateu.workflow.test;

import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateRejectedException;

/**
 * How an update a test sent ended: applied with a result, refused by a guard, not accepted by the process,
 * failed — or turned away at the door (an invalid payload, an update the definition does not declare).
 * Assert on it with {@link WorkflowAssertions#assertThat(UpdateOutcome)}.
 */
public final class UpdateOutcome {

    /** What happened to the update. */
    public enum Status {
        /** Applied: its variables are written and {@link #result()} is its reply. */
        COMPLETED,
        /** A {@code validate} guard said no; {@link #error()} is its message. Nothing changed. */
        REFUSED,
        /** The process was not accepting it (finished, failed, paused, the step it needs not waiting). */
        NOT_ACCEPTED,
        /** An expression of the update could not be evaluated. Nothing changed. */
        FAILED,
        /** The payload is not JSON, or not what the update's {@code accepts} schema allows (a 400). */
        INVALID_PAYLOAD,
        /** The process's definition declares no update of that name (a 404). */
        UNKNOWN_UPDATE,
        /** The idempotency key was used for a different request (a 422). */
        KEY_REUSED
    }

    private final Status status;
    private final String result;
    private final String error;
    private final String updateId;
    private final TestProcess process;
    private final boolean started;

    UpdateOutcome(Status status, String result, String error, String updateId, TestProcess process, boolean started) {
        this.status = status;
        this.result = result;
        this.error = error;
        this.updateId = updateId;
        this.process = process;
        this.started = started;
    }

    static UpdateOutcome of(ProcessUpdate update, TestProcess process, boolean started) {
        var status = switch (update.status()) {
            case COMPLETED -> Status.COMPLETED;
            case REFUSED -> Status.REFUSED;
            case NOT_ACCEPTED -> Status.NOT_ACCEPTED;
            case FAILED -> Status.FAILED;
            case REQUESTED -> throw new AssertionError("Update '" + update.name() + "' of " + process
                    + " was not applied (the process was busy?)");
        };
        return new UpdateOutcome(status, update.result(), update.error(), update.id(), process, started);
    }

    static UpdateOutcome of(ProcessUpdateRejectedException rejected, TestProcess process) {
        var status = switch (rejected.reason()) {
            case INVALID_PAYLOAD -> Status.INVALID_PAYLOAD;
            case NO_SUCH_UPDATE -> Status.UNKNOWN_UPDATE;
            case NOT_ACCEPTING, BUSINESS_KEY_TAKEN -> Status.NOT_ACCEPTED;
            case REFUSED -> Status.REFUSED;
            case FAILED -> Status.FAILED;
            case IDEMPOTENCY_KEY_REUSED -> Status.KEY_REUSED;
            case NO_SUCH_PROCESS, OVERLOADED, UNAVAILABLE -> throw new AssertionError(rejected.getMessage(), rejected);
        };
        return new UpdateOutcome(status, null, rejected.getMessage(), null, process, false);
    }

    public Status status() {
        return status;
    }

    /** The reply (JSON text) of a completed update; null otherwise. */
    public String result() {
        return result;
    }

    /** The reply parsed: a JSON object is a map, an array a list; null when there is none. */
    public Object resultValue() {
        return result == null ? null : Values.parsed(result);
    }

    /** Why it was refused, not accepted, failed or turned away; null for a completed update. */
    public String error() {
        return error;
    }

    /** The recorded update's id; null when it was turned away before being recorded. */
    public String updateId() {
        return updateId;
    }

    /** The process the update went to (for an update-with-start, the one it found or started); null if none. */
    public TestProcess process() {
        return process;
    }

    /** Whether an update-with-start started its process. */
    public boolean started() {
        return started;
    }

    @Override
    public String toString() {
        return "update " + status + (result != null ? " → " + result : "") + (error != null ? " (" + error + ")" : "");
    }
}
