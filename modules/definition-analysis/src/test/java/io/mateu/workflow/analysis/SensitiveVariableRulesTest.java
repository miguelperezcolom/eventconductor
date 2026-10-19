package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveVariableRulesTest {

    @Test
    void namesAndGlobsAreValidPatterns() {
        assertThat(SensitiveVariableRules.violations(List.of("cardNumber", "patient.*", "card?", "*_secret", "x-y"), null)).isEmpty();
        assertThat(SensitiveVariableRules.violations(null, null)).isEmpty();
    }

    @Test
    void malformedRepeatedOrTooManyPatternsAreRefused() {
        assertThat(SensitiveVariableRules.violations(List.of("card number"), "sensitiveVariables"))
                .singleElement().asString().contains("'card number' is not a variable name or a glob");
        assertThat(SensitiveVariableRules.violations(List.of("a", "a"), null))
                .singleElement().asString().contains("declared more than once");
        assertThat(SensitiveVariableRules.violations(Collections.singletonList(null), null))
                .singleElement().asString().contains("every entry must be");
        assertThat(SensitiveVariableRules.violations(List.of("x".repeat(129)), null))
                .singleElement().asString().contains("longer than 128");
        var many = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "v" + i).toList();
        assertThat(SensitiveVariableRules.violations(many, null)).anyMatch(v -> v.contains("at most 100"));
    }

    @Test
    void globsMatchAndAFieldGlobCoversItsRoot() {
        var sensitive = SensitiveVariableRules.matcher(List.of("cardNumber", "patient.*", "*Token", "pin?"));
        assertThat(sensitive.test("cardNumber")).isTrue();
        assertThat(sensitive.test("cardnumber")).isFalse();
        assertThat(sensitive.test("patient")).isTrue();
        assertThat(sensitive.test("patient.ssn")).isTrue();
        assertThat(sensitive.test("patientId")).isFalse();
        assertThat(sensitive.test("accessToken")).isTrue();
        assertThat(sensitive.test("pin1")).isTrue();
        assertThat(sensitive.test("pin12")).isFalse();
        assertThat(sensitive.test(null)).isFalse();
        assertThat(SensitiveVariableRules.matcher(List.of()).test("anything")).isFalse();
        assertThat(SensitiveVariableRules.matches("a.b", "a.b")).isTrue();
        assertThat(SensitiveVariableRules.matches("a.b", "aXb")).isFalse();
    }

    @Test
    void effectivePatternsAddTheOutputsOfSensitiveSteps() {
        assertThat(SensitiveVariableRules.effectivePatterns(List.of("cardNumber", " pan "), List.of(List.of("pan", "cvv"))))
                .containsExactly("cardNumber", "pan", "cvv");
        assertThat(SensitiveVariableRules.effectivePatterns(null, null)).isEmpty();
    }

    @Test
    void aSensitiveOutputsFlagNeedsOutputs() {
        assertThat(SensitiveVariableRules.stepViolations(true, List.of(), "step 'x'"))
                .singleElement().asString().contains("declares no outputVariables");
        assertThat(SensitiveVariableRules.stepViolations(true, List.of("pan"), "step 'x'")).isEmpty();
        assertThat(SensitiveVariableRules.stepViolations(null, null, "step 'x'")).isEmpty();
    }

    @Test
    void expressionsAreReadForTheVariablesTheyName() {
        var sensitive = SensitiveVariableRules.matcher(List.of("cardNumber", "patient.*"));
        assertThat(SensitiveVariableRules.sensitiveReferences("cardNumber.substring(0, 4) + 'x'", sensitive))
                .containsExactly("cardNumber");
        assertThat(SensitiveVariableRules.sensitiveReferences("patient.address.city", sensitive)).containsExactly("patient");
        assertThat(SensitiveVariableRules.sensitiveReferences("'cardNumber' + other", sensitive)).isEmpty();
        assertThat(SensitiveVariableRules.sensitiveReferences("x.cardNumber", sensitive)).isEmpty();
        assertThat(SensitiveVariableRules.templateReferences("Pay ${cardNumber} for ${amount}", sensitive))
                .containsExactly("cardNumber");
        assertThat(SensitiveVariableRules.templateReferences("no template", sensitive)).isEmpty();
        assertThat(SensitiveVariableRules.templateReferences("${unterminated", sensitive)).isEmpty();
    }

    @Test
    void aSearchAttributeOverASensitiveVariableIsRefused() {
        var sensitive = SensitiveVariableRules.matcher(List.of("cardNumber"));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("customerId", "${customerId}");
        attributes.put("card", Map.of("value", "${cardNumber}", "type", "keyword"));
        assertThat(SensitiveVariableRules.searchAttributeViolations(attributes, sensitive))
                .singleElement().asString().contains("searchAttributes.card reads the sensitive variable(s) cardNumber");
        assertThat(SensitiveVariableRules.searchAttributeViolations(null, sensitive)).isEmpty();
    }

    @Test
    void aCorrelationOrLockKeyOverASensitiveVariableIsRefused() {
        var sensitive = SensitiveVariableRules.matcher(List.of("cardNumber"));
        assertThat(SensitiveVariableRules.keyViolations("cardNumber", sensitive, "step 'w' correlationExpression"))
                .singleElement().asString().contains("stored in clear");
        assertThat(SensitiveVariableRules.keyViolations("card-${cardNumber}", sensitive, "step 'l' lockKey")).hasSize(1);
        assertThat(SensitiveVariableRules.keyViolations("orderId", sensitive, "x")).isEmpty();
        assertThat(SensitiveVariableRules.keyViolations(null, sensitive, "x")).isEmpty();
    }

    @Test
    void anLlmPromptOverASensitiveVariableNeedsTheOptIn() {
        var sensitive = SensitiveVariableRules.matcher(List.of("patient.*"));
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("system", "You are a nurse");
        llm.put("prompt", "Summarise ${patient.history}");
        assertThat(SensitiveVariableRules.llmViolations(llm, sensitive, "step 'summary'"))
                .singleElement().asString().contains("llm.allowSensitiveVariables: true");
        llm.put("allowSensitiveVariables", true);
        assertThat(SensitiveVariableRules.llmViolations(llm, sensitive, "step 'summary'")).isEmpty();
        assertThat(SensitiveVariableRules.llmViolations(null, sensitive, "x")).isEmpty();
    }

    @Test
    void aMigrationWarnsWhenSensitivityChanges() {
        var forProcess = MigrationRules.sensitivityProblems(List.of("pin"), List.of("cardNumber", "patient.*"),
                List.of("cardNumber", "pin", "patient", "amount"));
        assertThat(forProcess).extracting(MigrationRules.Problem::severity).containsOnly(MigrationRules.Severity.WARNING);
        assertThat(forProcess).extracting(MigrationRules.Problem::message)
                .anySatisfy(m -> assertThat(m).contains("[cardNumber, patient] become sensitive"))
                .anySatisfy(m -> assertThat(m).contains("[pin] stop being sensitive"));
        assertThat(MigrationRules.sensitivityProblems(List.of("a"), List.of("a"), List.of("a", "b"))).isEmpty();
        var definitionsOnly = MigrationRules.sensitivityProblems(List.of("pin"), List.of("cardNumber"), null);
        assertThat(definitionsOnly).extracting(MigrationRules.Problem::message)
                .anySatisfy(m -> assertThat(m).contains("[cardNumber] are added"))
                .anySatisfy(m -> assertThat(m).contains("[pin] are removed"));
        assertThat(MigrationRules.sensitivityProblems(null, null, null)).isEmpty();
    }

    @Test
    void theEffectivePatternsOfAGenericDefinition() {
        Map<String, Object> definition = Map.of("sensitiveVariables", List.of("cardNumber"),
                "steps", List.of(Map.of("id", "t", "sensitiveOutputs", true, "outputVariables", List.of("pan")),
                        Map.of("id", "u", "outputVariables", List.of("plain"))));
        assertThat(SensitiveVariableRules.patternsOf(definition)).containsExactly("cardNumber", "pan");
        assertThat(SensitiveVariableRules.patternsOf(null)).isEmpty();
    }

    @Test
    void anAgentReadingASensitiveVariableNeedsTheOptIn() {
        var sensitive = SensitiveVariableRules.matcher(List.of("cardNumber", "patient.*"));
        Map<String, Object> viaGoal = new java.util.LinkedHashMap<>(Map.of("connection", "claude",
                "goal", "Dispute the charge on ${cardNumber}", "system", "Be brief"));
        assertThat(SensitiveVariableRules.agentViolations(viaGoal, sensitive, "step 'agent'"))
                .singleElement().asString().contains("step 'agent': the agent reads the sensitive variable(s) cardNumber")
                .contains("agent.allowSensitiveVariables: true");
        Map<String, Object> viaTool = Map.of("goal", "Help", "tools", List.of(
                Map.of("name", "fetch", "http", Map.of("method", "GET", "url", "https://x.example/p/${patient.id}",
                        "headers", Map.of("X-Card", "${cardNumber}"))),
                Map.of("name", "lookup", "step", "lookup")));
        assertThat(SensitiveVariableRules.agentViolations(viaTool, sensitive, "step 'agent'"))
                .singleElement().asString().contains("patient").contains("cardNumber");
        viaGoal.put("allowSensitiveVariables", true);
        assertThat(SensitiveVariableRules.agentViolations(viaGoal, sensitive, "x")).isEmpty();
        assertThat(SensitiveVariableRules.agentViolations(Map.of("goal", "Help ${name}"), sensitive, "x")).isEmpty();
        assertThat(SensitiveVariableRules.agentViolations(null, sensitive, "x")).isEmpty();
        assertThat(SensitiveVariableRules.agentViolations(Map.of("tools", List.of(Map.of("http", Map.of("url", "${broken")))),
                sensitive, "x")).isEmpty();
    }
}
