package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class FormEntity {
    @Id
    private String id;

    String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Who may work on the form's tasks: a JSON array of scopes, null for none. */
    @Column(name = "required_scopes", columnDefinition = "TEXT")
    private String requiredScopes;

    /** Who may work on the form's tasks: a JSON array of roles, null for none. */
    @Column(name = "required_roles", columnDefinition = "TEXT")
    private String requiredRoles;

    public FormEntity(String id, String name, String description) {
        this(id, name, description, null, null);
    }

}
