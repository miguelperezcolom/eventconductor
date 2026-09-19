package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored task contract. Versioned, unlike rules or forms: the primary key is the
 * {@code <id>@<version>} reference, so several versions of one contract coexist as distinct rows,
 * and {@code contract_id} is indexed so "the latest version of this id" is a cheap lookup. The
 * contract itself is kept whole as JSON — read entire, never queried field by field — the same way
 * a rule or a layout is.
 *
 * <p>Declared as an entity so {@code ddl-auto} creates the table for the embedded/test paths; the
 * same shape is created by Flyway {@code V30} where migrations run instead.
 */
@Entity
@Table(name = "ec_task_contract", indexes = {
        @Index(name = "idx_task_contract_id", columnList = "contract_id, version")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TaskContractEntity {

    /** {@code <id>@<version>}. */
    @Id
    private String ref;

    @Column(name = "contract_id")
    private String contractId;

    private int version;

    @Column(name = "contract_group")
    private String group;

    private String topic;

    @Column(columnDefinition = "TEXT")
    private String contractJson;
}
