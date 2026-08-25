package io.mateu.workflow.domain;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;

import java.util.List;

public record Field(
        String id,
        String label,
        FieldDataType dataType,
        FieldStereotype stereotype,
        boolean required,
        String description,
        /**
         * The choices this field offers, for the stereotypes that pick from a list — {@code radio},
         * {@code select}, {@code combobox}, {@code listBox}, {@code choice}. Empty for every other
         * field, and empty is what a field that takes free input means.
         */
        List<FieldOption> options,
        /**
         * Where the choices come from instead, when they are not written into the definition: a
         * REST endpoint the browser calls as the form renders. A field declares one or the other,
         * never both — the schema rejects a field that declares both.
         */
        FieldOptionsSource optionsSource
) {

    public Field {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** A field whose choices, if any, are written into the definition. */
    public Field(String id, String label, FieldDataType dataType, FieldStereotype stereotype,
                 boolean required, String description, List<FieldOption> options) {
        this(id, label, dataType, stereotype, required, description, options, null);
    }

    /** A field that offers no fixed choices — the shape this record had before options existed. */
    public Field(String id, String label, FieldDataType dataType, FieldStereotype stereotype,
                 boolean required, String description) {
        this(id, label, dataType, stereotype, required, description, List.of(), null);
    }
}
