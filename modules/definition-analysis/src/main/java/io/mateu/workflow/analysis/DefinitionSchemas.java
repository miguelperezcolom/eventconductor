package io.mateu.workflow.analysis;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.AnnotationKeyword;

/**
 * The schema registry every validator of EventConductor's own bundled schemas (workflow definition,
 * form, rule, task contract, {@code cron.yaml}, {@code events.yaml}) loads them with — the engine on
 * import and the Maven plugin at build time — so both read them under the same dialect.
 *
 * <p>The bundled schemas declare draft-07 (what editors and IDE plugins validate them under, and whose
 * {@code $ref}-ignores-siblings semantics they are written for) but also use two later keywords:
 * {@code $defs}, the container their {@code $ref}s point into, and {@code deprecated}, an annotation on
 * removed fields. Under plain draft-07 the validator warns {@code Unknown keyword $defs} /
 * {@code Unknown keyword deprecated} for each of them on every load. Both are pure annotations to a
 * validator — a {@code $ref} resolves {@code #/$defs/...} by JSON pointer either way, and
 * {@code deprecated} never fails an instance — so this dialect is draft-07 with exactly those two
 * registered as annotation keywords. Validation is unchanged; a genuinely unknown keyword still warns.
 */
public final class DefinitionSchemas {

    /** Draft-07 plus {@code $defs} and {@code deprecated} as annotations; keeps the draft-07 id. */
    public static final Dialect DIALECT = Dialect.builder(Dialects.getDraft7())
            .keyword(new AnnotationKeyword("$defs"))
            .keyword(new AnnotationKeyword("deprecated"))
            .build();

    private DefinitionSchemas() {
    }

    /** A registry whose only (and default) dialect is {@link #DIALECT}. */
    public static SchemaRegistry registry() {
        return SchemaRegistry.withDialect(DIALECT);
    }
}
