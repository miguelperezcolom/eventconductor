package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.Identifiable;

/**
 * UI view of a Rule: metadata columns for the listing plus the full definition
 * (JSON or YAML) as the editable payload. The definition is the source of
 * truth on save.
 */
public record RuleRow(
        @HiddenInCreate
        String id,
        @HiddenInCreate
        String name,
        @HiddenInCreate
        String type,
        @HiddenInCreate
        int version,
        @HiddenInList
        @Colspan(2)
        @Stereotype(FieldStereotype.textarea)
        String definition
) implements Identifiable {

    @Override
    public String toString() {
        return name != null ? name : "New rule";
    }
}
