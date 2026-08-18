package io.mateu.workflow.domain;

/**
 * One choice offered by a field that picks from a fixed list — a radio group, a select, a combobox.
 *
 * <p>{@code value} is what the form submits, and what the process variable ends up holding, so it
 * is the half the workflow's guards are written against. {@code label} is what the person filling
 * the form reads. Keeping them apart is the point: a definition can say {@code REFUND} to the
 * engine and "Refund the guest" to the user without the two having to be the same string.
 *
 * <p>The label is optional and defaults to the value, so a list of plain codes needs no ceremony.
 */
public record FieldOption(String value, String label) {

    public FieldOption {
        label = label == null || label.isBlank() ? value : label;
    }

    /** An option that shows its own value. */
    public FieldOption(String value) {
        this(value, null);
    }
}
