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
        List<Field> fields,
        /**
         * Who may work on a task of this form: the scopes and roles a person must <b>all</b> hold to
         * see it in their list, to claim it, and to complete it. Empty — the default — means the form
         * adds no restriction and every authenticated person sees the task, which is how every form
         * behaved before this existed.
         *
         * <p>On the form rather than on the step because this is a property of the work, not of the
         * flow: the same "approve a refund" form is for the same people whichever workflow routes a
         * task to it, and putting it here means it is declared once and cannot drift between the
         * three or four definitions that use the form. The workflow's own
         * {@code requiredScopes}/{@code requiredRoles} answer a different question — who may
         * <em>start</em> a process — and the two are checked independently.
         *
         * <p>Enforced only when {@code workflow.security.flow-authorization.enabled}.
         */
        List<String> requiredScopes,
        List<String> requiredRoles
) implements Identifiable {

    public Form {
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
    }

    /** The shape before flow authorization existed: a form anybody may work on. */
    public Form(String id, String name, String description, List<Field> fields) {
        this(id, name, description, fields, List.of(), List.of());
    }

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
