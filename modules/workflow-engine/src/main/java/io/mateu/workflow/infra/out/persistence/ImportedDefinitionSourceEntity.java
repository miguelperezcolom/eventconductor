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
 * What one import source brought in last time — see {@link JpaImportedDefinitionsRegistry}. Declared
 * here as well as in {@code V46__dispatch_count_and_import_provenance.sql}, for the schemas
 * {@code ddl-auto} creates.
 */
@Entity
@Table(name = "imported_definition_source")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImportedDefinitionSourceEntity {

    /** Hash of namespace and source: the source can be a long path or url, too long for a key. */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "namespace", length = 64, nullable = false)
    private String namespace;

    @Column(name = "source_key", length = 1024, nullable = false)
    private String sourceKey;

    /** The imported ids, as a JSON array. */
    @Column(name = "ids_json", columnDefinition = "TEXT")
    private String idsJson;
}
