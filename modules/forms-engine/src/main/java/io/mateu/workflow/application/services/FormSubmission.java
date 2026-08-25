package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.input.InputLimits;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * What a submitted form is allowed to contain, checked against the form that was asked for.
 *
 * <p>A task is completed over HTTP by whoever holds the task, and a browser is not a trusted
 * source: the values that arrive are whatever was posted, not whatever the page rendered. Until
 * this existed nothing compared the two — every submitted name became a process variable, so a
 * form could be completed with fields it does not have and without the fields it says are
 * required. {@code required} was a client-side hint and nothing more, and the variables a
 * downstream step read were whoever-submitted's to choose.
 *
 * <p>Two different answers, because the two problems are different. A name the form does not
 * declare is <b>dropped</b>: that is the standard defence against mass assignment, and it has to be
 * dropping rather than refusing because the page's own state legitimately carries keys that are not
 * fields. A declared field that is required and absent is <b>refused</b>, because completing the
 * task without it is precisely what the form said must not happen.
 */
@Slf4j
public final class FormSubmission {

    /**
     * Longest accepted value, in characters. Generous — a {@code richText} or {@code textarea}
     * field can legitimately hold a great deal — and present so that a single POST cannot decide
     * how much memory the engine spends. Defaults to the engine-wide
     * {@link InputLimits#MAX_VALUE_LENGTH} so a value that would be refused arriving over Kafka is
     * refused arriving from a browser, and vice versa; the form-specific
     * {@code workflow.forms.max-value-length} still overrides it where a deployment wants the two
     * to differ.
     */
    public static final int MAX_VALUE_LENGTH =
            Integer.getInteger("workflow.forms.max-value-length", InputLimits.MAX_VALUE_LENGTH);

    /**
     * Most values one submission may carry, counted before they are filtered against the form.
     * The filtering is what protects the process — an undeclared name never becomes a variable —
     * but it happens after every submitted value has been held in memory and compared, so a POST
     * with a million of them is answered by dropping a million of them. Counted first, and against
     * everything submitted rather than against what the form declares, because that is the number
     * that decides the work. Override with {@code workflow.forms.max-values}.
     */
    public static final int MAX_VALUES =
            Integer.getInteger("workflow.forms.max-values", InputLimits.MAX_VARIABLES);

    private FormSubmission() {
    }

    /**
     * The submitted values, reduced to the ones this form actually declares.
     *
     * @throws IncompleteSubmissionException if a required field is missing or blank
     * @throws OversizedValueException       if a value is longer than {@link #MAX_VALUE_LENGTH},
     *                                       or there are more than {@link #MAX_VALUES} of them
     */
    public static List<Value> accepted(Form form, List<Value> submitted, String taskId) {
        var values = submitted == null ? List.<Value>of() : submitted;
        if (form == null) {
            // No form to check against — the task names one that is not there. Nothing can be said
            // about which names are legitimate, so nothing is accepted rather than everything.
            log.warn("Task {} names a form that is not in the catalogue; its {} submitted value(s)"
                    + " cannot be checked and are not accepted", taskId, values.size());
            return List.of();
        }
        if (values.size() > MAX_VALUES) {
            throw new OversizedValueException(
                    "Form '" + form.name() + "' was submitted with " + values.size()
                            + " values, over the limit of " + MAX_VALUES);
        }
        var declared = form.fields() == null ? Set.<String>of()
                : form.fields().stream().map(Field::id).collect(java.util.stream.Collectors.toSet());

        for (var value : values) {
            if (value.value() != null && value.value().length() > MAX_VALUE_LENGTH) {
                throw new OversizedValueException(
                        "Field '" + value.name() + "' of form '" + form.name() + "' was submitted with "
                                + value.value().length() + " characters, over the "
                                + MAX_VALUE_LENGTH + " character limit");
            }
        }

        var accepted = values.stream().filter(value -> declared.contains(value.name())).toList();
        if (accepted.size() != values.size()) {
            log.warn("Task {}: dropped {} submitted value(s) naming no field of form '{}' — {}",
                    taskId, values.size() - accepted.size(), form.name(),
                    values.stream().map(Value::name).filter(name -> !declared.contains(name)).toList());
        }

        var missing = (form.fields() == null ? List.<Field>of() : form.fields()).stream()
                .filter(Field::required)
                .map(Field::id)
                .filter(id -> accepted.stream()
                        .noneMatch(value -> id.equals(value.name())
                                && value.value() != null && !value.value().isBlank()))
                .toList();
        if (!missing.isEmpty()) {
            throw new IncompleteSubmissionException(
                    "Form '" + form.name() + "' cannot be completed without " + missing);
        }
        return accepted;
    }

    /** A required field was missing or blank. */
    public static class IncompleteSubmissionException extends RuntimeException {
        public IncompleteSubmissionException(String message) {
            super(message);
        }
    }

    /** A single value, or the submission as a whole, was larger than the engine accepts. */
    public static class OversizedValueException extends RuntimeException {
        public OversizedValueException(String message) {
            super(message);
        }
    }
}
