package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.RuleValidator.RuleValidationException;
import io.mateu.workflow.domain.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-RULEDEF-01..08 — a rule definition written to break the catalogue.
 *
 * <p>Rules arrive the way workflows and forms do: a git repository, a watched directory, the
 * editor. A bad one decides business outcomes, so the bar is the same — refused with a message
 * naming what is wrong, or accepted and unable to hurt the runtime (which
 * {@code HostileRuleEvaluationTest} is the other half of).
 */
class HostileRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuleValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new RuleValidator();
        validator.init();
    }

    private Rule parse(String json) throws Exception {
        return objectMapper.readValue(json, Rule.class);
    }

    /** What the import paths do: validate the document as written, before it is bound. */
    private void validateAsAFile(String json) throws Exception {
        validator.validateSource(objectMapper.readTree(json), "hostile.ecrule");
    }

    /** HARD-RULEDEF-01. An expression that is not one is refused, with the expression quoted back. */
    @Test
    void anUnparseableExpressionIsRefused() throws Exception {
        assertThatThrownBy(() -> validator.validate(parse("""
                { "id": "r", "name": "Broken", "type": "expression", "when": "amount > 100 &&",
                  "then": [ { "name": "out", "expression": "1" } ] }
                """)))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("invalid JEXL expression");
    }

    /** HARD-RULEDEF-02. So is one that parses and is far too big to be a business rule. */
    @Test
    void anOversizedExpressionIsRefused() throws Exception {
        var huge = "true" + " || true".repeat(2000);

        assertThatThrownBy(() -> validator.validate(parse("""
                { "id": "r", "name": "Huge", "type": "expression", "when": "%s",
                  "then": [ { "name": "out", "expression": "1" } ] }
                """.formatted(huge))))
                .isInstanceOf(RuleValidationException.class);
    }

    /** HARD-RULEDEF-03. A decision table is inputs, outputs and rows, or it is not a table. */
    @Test
    void aDecisionTableMissingItsPartsIsRefused() throws Exception {
        assertThatThrownBy(() -> validator.validate(parse("""
                { "id": "r", "name": "Half a table", "type": "decision-table",
                  "inputs": ["amount"], "outputs": ["discount"] }
                """)))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("rows");
    }

    /** HARD-RULEDEF-04. A row with fewer cells than the table has columns, named by row. */
    @Test
    void aRaggedRowIsRefused() throws Exception {
        assertThatThrownBy(() -> validator.validate(parse("""
                { "id": "r", "name": "Ragged", "type": "decision-table",
                  "inputs": ["amount", "country"], "outputs": ["discount"],
                  "rows": [ { "when": ["> 100", "'ES'"], "then": ["10"] },
                            { "when": ["> 100"], "then": ["5"] } ] }
                """)))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("row 1");
    }

    /** HARD-RULEDEF-05. A cell that compiles to nonsense is caught where the cell is, not at runtime. */
    @Test
    void aCellThatCompilesToInvalidJexlIsRefused() throws Exception {
        assertThatThrownBy(() -> validator.validate(parse("""
                { "id": "r", "name": "Bad cell", "type": "decision-table",
                  "inputs": ["amount"], "outputs": ["discount"],
                  "rows": [ { "when": ["> >"], "then": ["10"] } ] }
                """)))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("when[0]");
    }

    /**
     * HARD-RULEDEF-06. A misspelled key changes what the rule does. {@code hitpolicy} for
     * {@code hitPolicy} would take FIRST — the default — where its author asked for COLLECT, so a
     * table meant to accumulate every match returns only the first.
     *
     * <p>Refused twice over here, and it is worth saying why the belt and the braces both exist.
     * The rule importer binds with a plain {@code ObjectMapper}, which fails on an unknown property,
     * so the misspelling never survived binding — unlike the workflow importer, which binds
     * leniently and is where the same misspelling really did import clean (HARD-DEF-07). The
     * document-level check makes the refusal independent of that: it is the schema saying so,
     * before any binder is involved, with the offending key named.
     */
    @Test
    void aMisspelledKeyIsRefused() throws Exception {
        var withTypo = """
                { "id": "r", "name": "Typo", "type": "decision-table", "hitpolicy": "COLLECT",
                  "inputs": ["amount"], "outputs": ["discount"],
                  "rows": [ { "when": ["> 100"], "then": ["10"] } ] }
                """;

        assertThatThrownBy(() -> validateAsAFile(withTypo))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("hitpolicy");

        assertThatThrownBy(() -> parse(withTypo))
                .as("and binding refuses it too — the two agree rather than one covering for the other")
                .isInstanceOf(Exception.class);
    }

    /** HARD-RULEDEF-07. A rule type the runtime has no branch for. */
    @Test
    void anUnknownRuleTypeIsRefused() {
        assertThatThrownBy(() -> validateAsAFile("""
                { "id": "r", "name": "Alien", "type": "shell-script", "when": "true" }
                """))
                .isInstanceOf(Exception.class);
    }

    /**
     * HARD-RULEDEF-08. A rule carrying an exploit in its expression is accepted by the catalogue
     * and defused by the sandbox. Refusing it here would mean the catalogue deciding what JEXL is
     * allowed to say, which is the sandbox's job and which it does at the only moment it can be
     * done properly — evaluation. See {@code HostileRuleEvaluationTest}.
     */
    @Test
    void anExploitInAnExpressionIsAcceptedByTheCatalogueAndDefusedAtEvaluation() {
        assertThatNoException().isThrownBy(() -> validator.validate(parse("""
                { "id": "r", "name": "'; DROP TABLE rule_entity; --", "type": "expression",
                  "when": "true",
                  "then": [ { "name": "out",
                              "expression": "''.getClass().forName('java.lang.Runtime')" } ] }
                """)));
    }
}
