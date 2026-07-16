package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Rules are stored as their canonical JSON (decision tables are too nested for
 * an entity-per-field model); the extra columns exist for listing/searching.
 */
@Entity
@Table(name = "ec_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleEntity {

    @Id
    private String id;

    private String name;

    private String type;

    private int version;

    @Column(columnDefinition = "TEXT")
    private String ruleJson;
}
