package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * A field of a form.
 *
 * <p>Identified by <em>form plus field</em>, not by field alone: {@code form-schema.json} declares a
 * field id unique only <em>within</em> its form, and git-imported definitions use human slugs
 * ({@code approved}, {@code comment}) that repeat freely across forms. With the field id as the sole
 * key, saving a second form that reused one re-parented the existing row and the first form silently
 * lost the field.
 */
@Entity
@IdClass(FieldEntity.Key.class)
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class FieldEntity {

    @Id
    private String formId;

    @Id
    private String id;

    private String label;

    private String dataType;

    private String stereotype;

    private boolean required;

    private String description;

    /**
     * Position within the form. Fields are an ordered list — the form renders them top to bottom —
     * and a plain {@code SELECT} returns rows in whatever order the database finds convenient, so
     * the order has to be stored to survive a round trip.
     */
    private int fieldOrder;

    @Getter@Setter
    @NoArgsConstructor@AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String formId;
        private String id;
    }
}
