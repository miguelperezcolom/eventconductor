package io.mateu.workflow.application.services;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.services.FormSubmission.IncompleteSubmissionException;
import io.mateu.workflow.application.services.FormSubmission.OversizedValueException;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-SUB-01..07 — what a submitted form is allowed to contain.
 *
 * <p>A task is completed over HTTP by whoever holds it, and what arrives is whatever was posted —
 * not whatever the page rendered. Until this was checked, every submitted name became a process
 * variable: a form could be completed with fields it does not have, and without the fields it says
 * are required. {@code required} was a client-side hint, and which variables a process carried into
 * its next step was the submitter's to choose.
 */
class HostileSubmissionTest {

    private Field field(String id, boolean required) {
        return new Field(id, "Label " + id, FieldDataType.string, FieldStereotype.regular, required, "");
    }

    private Form form(Field... fields) {
        return new Form("f-1", "Payment", "", List.of(fields));
    }

    private List<Value> accepted(Form form, Value... submitted) {
        return FormSubmission.accepted(form, List.of(submitted), "task-1");
    }

    /** HARD-SUB-01. What the form asked for gets through untouched. */
    @Test
    void theValuesTheFormDeclaresAreAccepted() {
        var result = accepted(form(field("amount", false), field("currency", false)),
                new Value("amount", "100"), new Value("currency", "EUR"));

        assertThat(result).containsExactly(new Value("amount", "100"), new Value("currency", "EUR"));
    }

    /**
     * HARD-SUB-02. Mass assignment, which is the whole reason this exists. A submitted name the
     * form does not declare is dropped rather than refused: the page's own component state
     * legitimately carries keys that are not fields, so refusing would break the UI, while
     * accepting would let a crafted POST decide what the next step reads.
     */
    @Test
    void aValueNamingNoFieldOfTheFormIsDropped() {
        var result = accepted(form(field("amount", false)),
                new Value("amount", "100"),
                new Value("approved", "true"),
                new Value("role", "admin"));

        assertThat(result)
                .as("only the field the form declares survives")
                .containsExactly(new Value("amount", "100"));
    }

    /** HARD-SUB-03. A required field that is not there stops the completion. */
    @Test
    void aMissingRequiredFieldIsRefused() {
        assertThatThrownBy(() -> accepted(form(field("amount", true), field("note", false)),
                new Value("note", "later")))
                .isInstanceOf(IncompleteSubmissionException.class)
                .hasMessageContaining("amount");
    }

    /** HARD-SUB-04. And so is one submitted empty, which is the same thing said politely. */
    @Test
    void aBlankRequiredFieldIsRefused() {
        assertThatThrownBy(() -> accepted(form(field("amount", true)), new Value("amount", "   ")))
                .isInstanceOf(IncompleteSubmissionException.class);
    }

    /**
     * HARD-SUB-05. A required field cannot be satisfied by a name the form does not declare —
     * the check has to run after the drop, or dropping would be the way around it.
     */
    @Test
    void anUndeclaredNameCannotSatisfyARequiredField() {
        assertThatThrownBy(() -> accepted(form(field("amount", true)),
                new Value("Amount", "100"), new Value("amount ", "100")))
                .isInstanceOf(IncompleteSubmissionException.class)
                .hasMessageContaining("amount");
    }

    /** HARD-SUB-06. One POST does not get to decide how much memory the engine spends. */
    @Test
    void anOversizedValueIsRefused() {
        var huge = "x".repeat(FormSubmission.MAX_VALUE_LENGTH + 1);

        assertThatThrownBy(() -> accepted(form(field("note", false)), new Value("note", huge)))
                .isInstanceOf(OversizedValueException.class)
                .hasMessageContaining("character limit");
    }

    /** …and a large-but-reasonable one is not: a textarea holds a lot, legitimately. */
    @Test
    void aLargeButReasonableValueIsAccepted() {
        var big = "x".repeat(FormSubmission.MAX_VALUE_LENGTH);

        assertThat(accepted(form(field("note", false)), new Value("note", big))).hasSize(1);
    }

    /**
     * HARD-SUB-07. Hostile <em>content</em> is content. A value that reads as SQL or as a script
     * tag is accepted and passed through exactly as sent — it is escaped where it is rendered and
     * parameterised where it is queried, not mangled on the way in.
     */
    @Test
    void hostileContentInADeclaredFieldIsAcceptedUnchanged() {
        var payload = "'; DROP TABLE form_execution_entity; --<script>alert(1)</script>";

        assertThat(accepted(form(field("note", false)), new Value("note", payload)))
                .containsExactly(new Value("note", payload));
    }

    /**
     * HARD-SUB-08. A task whose form is gone accepts nothing. Nothing can be said about which
     * names are legitimate, and "cannot check" must not read as "everything is fine".
     */
    @Test
    void nothingIsAcceptedWhenTheFormIsNotInTheCatalogue() {
        assertThat(FormSubmission.accepted(null, List.of(new Value("amount", "100")), "task-1")).isEmpty();
    }
}
