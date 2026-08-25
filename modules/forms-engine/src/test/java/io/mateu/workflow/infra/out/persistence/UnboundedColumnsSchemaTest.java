package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The columns that hold as much as their author needed, asserted on the schema Hibernate builds
 * from the mappings.
 *
 * <p><b>Why the schema and not a long value.</b> A real deployment could not write a form execution
 * at all — {@code value too long for type character varying(255)} — because these two JSON columns
 * were declared TEXT in the migration and nowhere in the mapping, so anything that let Hibernate
 * build its own schema got {@code varchar(255)}. The obvious test is to save several kilobytes and
 * read them back, and it is worthless here: H2 does not enforce VARCHAR length, so that test passes
 * against a 255-character column holding 2 KB. Verified, not assumed — with the mapping reverted
 * the column is {@code CHARACTER VARYING len=255} and the round trip is still green.
 *
 * <p>That is the same blind spot that let the bug ship, and asserting the declared width closes it
 * without needing a PostgreSQL container: the width is what differs, and H2 reports it faithfully
 * even though it will not police it.
 */
@DataJpaTest
class UnboundedColumnsSchemaTest {

    @Autowired
    EntityManager entityManager;

    @ParameterizedTest(name = "{0}.{1} is not capped")
    @CsvSource({
            "form_execution_entity, variables",
            "form_execution_entity, values",
            "field_entity,          options",
            "field_entity,          options_source",
            "field_entity,          description",
            "form_entity,           description",
    })
    void columnIsWideEnoughForWhateverItIsGiven(String table, String column) {
        assertThat(declaredWidthOf(table, column))
                .as("%s.%s must not be capped: it holds JSON or free text, and in PostgreSQL a "
                        + "length is a constraint rather than an optimisation", table, column)
                .isGreaterThan(255L);
    }

    private long declaredWidthOf(String table, String column) {
        var width = entityManager.createNativeQuery("""
                        select character_maximum_length
                        from information_schema.columns
                        where lower(table_name) = lower(:t) and lower(column_name) = lower(:c)
                        """)
                .setParameter("t", table.trim())
                .setParameter("c", column.trim())
                .getResultList();
        assertThat(width).as("no column %s.%s in the generated schema", table, column).hasSize(1);
        return ((Number) width.get(0)).longValue();
    }
}
