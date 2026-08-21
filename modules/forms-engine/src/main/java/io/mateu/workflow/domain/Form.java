package io.mateu.workflow.domain;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.Identifiable;

import java.util.List;

@Style("width: 100%;")
public record Form(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInCreate
        String id,
        String name,
        String description,
        @Colspan(2)
        @MasterDetail(minHeightWhenDetailVisible = "26rem;")
        List<Field> fields
) implements Identifiable {

    /**
     * What the schema cannot say.
     *
     * <p>A field id is the name of the process variable that field's answer becomes, so two fields
     * sharing one is not a cosmetic clash: which of them a submitted value belongs to is arbitrary,
     * the value a downstream step reads is therefore arbitrary too, and a required field can be
     * satisfied by whatever its namesake happened to collect. JSON Schema can require each id to be
     * present and non-empty — it does — but it has no way to say that they must differ from one
     * another, which is why this is here and not in {@code form-schema.json}.
     *
     * @throws IllegalStateException if two fields share an id
     */
    public void checkInvariants() {
        if (fields == null) {
            return;
        }
        var seen = new java.util.HashSet<String>();
        for (var field : fields) {
            if (field.id() != null && !seen.add(field.id())) {
                throw new IllegalStateException(
                        "Duplicate field id '" + field.id() + "' in form '" + name + "'.");
            }
        }
    }

    @Override
    public String toString() {
        return name != null?name:"New form";
    }
}
