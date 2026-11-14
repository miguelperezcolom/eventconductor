package io.mateu.workflow.analysis;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionSchemasTest {

    /** Draft-07 by its id, with a $defs container its $ref points into and a deprecated annotation. */
    static final String SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "type": {"$ref": "#/$defs/stepType"},
                "legacy": {"type": "string", "deprecated": true}
              },
              "additionalProperties": false,
              "$defs": {
                "stepType": {"enum": ["START", "ACTION"]}
              }
            }
            """;

    @Test
    void isDraftSevenWithDefsAndDeprecatedAsAnnotations() {
        var dialect = DefinitionSchemas.DIALECT;
        assertThat(dialect.getSpecificationVersion()).isEqualTo(SpecificationVersion.DRAFT_7);
        assertThat(dialect.getId()).isEqualTo(SpecificationVersion.DRAFT_7.getDialectId());
        // Registered, so the validator no longer falls back to its "Unknown keyword" factory for them.
        assertThat(dialect.getKeywords()).containsKeys("$defs", "deprecated", "$ref", "enum");
    }

    @Test
    void realViolationsStillSurface() {
        var schema = DefinitionSchemas.registry().getSchema(SCHEMA, InputFormat.JSON);

        assertThat(schema.validate("{\"type\": \"ACTION\", \"legacy\": \"still allowed\"}", InputFormat.JSON)).isEmpty();
        // Through the $ref into $defs: an enum violation.
        assertThat(schema.validate("{\"type\": \"NOPE\"}", InputFormat.JSON))
                .singleElement().satisfies(error -> assertThat(error.getInstanceLocation().toString()).contains("type"));
        // A misspelled key.
        assertThat(schema.validate("{\"typo\": 1}", InputFormat.JSON)).isNotEmpty();
        // deprecated annotates; it never fails an instance, but the field's own type still applies.
        assertThat(schema.validate("{\"legacy\": 3}", InputFormat.JSON)).isNotEmpty();
    }
}
