package io.mateu.workflow.test;

import org.assertj.core.api.AbstractAssert;

/** AssertJ assertions on an {@link UpdateOutcome}. */
public class UpdateAssert extends AbstractAssert<UpdateAssert, UpdateOutcome> {

    UpdateAssert(UpdateOutcome actual) {
        super(actual, UpdateAssert.class);
    }

    public UpdateAssert hasStatus(UpdateOutcome.Status expected) {
        isNotNull();
        if (actual.status() != expected) {
            failWithMessage("Expected the update to be %s but it was %s", expected, actual);
        }
        return this;
    }

    public UpdateAssert isCompleted() {
        return hasStatus(UpdateOutcome.Status.COMPLETED);
    }

    public UpdateAssert isRefused() {
        return hasStatus(UpdateOutcome.Status.REFUSED);
    }

    /** Refused by a guard whose message contains {@code fragment}. */
    public UpdateAssert isRefusedWith(String fragment) {
        isRefused();
        return hasErrorContaining(fragment);
    }

    public UpdateAssert isNotAccepted() {
        return hasStatus(UpdateOutcome.Status.NOT_ACCEPTED);
    }

    public UpdateAssert isFailed() {
        return hasStatus(UpdateOutcome.Status.FAILED);
    }

    public UpdateAssert isInvalidPayload() {
        return hasStatus(UpdateOutcome.Status.INVALID_PAYLOAD);
    }

    /** Completed with this result (compared as JSON, not as text). */
    public UpdateAssert hasResult(String expectedJson) {
        isCompleted();
        if (actual.result() == null || !Values.tree(expectedJson).equals(Values.tree(actual.result()))) {
            failWithMessage("Expected the update to answer %s but it answered %s", expectedJson, actual.result());
        }
        return this;
    }

    public UpdateAssert hasErrorContaining(String fragment) {
        isNotNull();
        if (actual.error() == null || !actual.error().contains(fragment)) {
            failWithMessage("Expected the update's error to contain '%s' but it was: %s", fragment, actual.error());
        }
        return this;
    }

    /** The update-with-start started its process. */
    public UpdateAssert startedTheProcess() {
        isNotNull();
        if (!actual.started()) {
            failWithMessage("Expected the update-with-start to start a process, but it went to an existing one: %s", actual);
        }
        return this;
    }
}
