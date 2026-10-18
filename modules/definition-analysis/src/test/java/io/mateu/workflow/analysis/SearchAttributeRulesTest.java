package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAttributeRulesTest {

    @Test
    void acceptsTheShorthandAndTheObjectForm() {
        var declared = new LinkedHashMap<String, Object>();
        declared.put("customerId", "${customerId}");
        declared.put("amount", Map.of("value", "${total}", "type", "number"));
        declared.put("region", Map.of("value", "${address.region}"));
        declared.put("label", Map.of("value", "order ${orderId}", "type", "TEXT"));
        assertThat(SearchAttributeRules.violations(declared, "searchAttributes")).isEmpty();
        assertThat(SearchAttributeRules.declarations(declared))
                .extracting(SearchAttributeRules.Declaration::type)
                .containsExactly("keyword", "number", "keyword", "text");
    }

    @Test
    void refusesBadNamesTypesValuesAndKeys() {
        var declared = new LinkedHashMap<String, Object>();
        declared.put("9lives", "${x}");
        declared.put("amount", Map.of("value", "${total}", "type", "money"));
        declared.put("constant", "always");
        declared.put("broken", "${unterminated");
        declared.put("extra", Map.of("value", "${x}", "index", true));
        declared.put("number", 12);
        declared.put("empty", Map.of("type", "keyword"));
        var violations = SearchAttributeRules.violations(declared, "searchAttributes");
        assertThat(violations).hasSize(7);
        assertThat(String.join("\n", violations))
                .contains("'9lives' is not a valid search attribute name")
                .contains("unknown type 'money'")
                .contains("has no ${…} expression")
                .contains("Unterminated")
                .contains("unknown key 'index'")
                .contains("must be a template string")
                .contains("'empty' has no value");
    }

    @Test
    void capsTheCountAndRefusesDuplicatesIgnoringCase() {
        var declared = new LinkedHashMap<String, Object>();
        for (int i = 0; i <= SearchAttributeRules.MAX_ATTRIBUTES; i++) {
            declared.put("a" + i, "${v}");
        }
        assertThat(SearchAttributeRules.violations(declared, null)).anyMatch(v -> v.contains("at most 20"));
        var twice = new LinkedHashMap<String, Object>();
        twice.put("Region", "${r}");
        twice.put("region", "${r}");
        assertThat(SearchAttributeRules.violations(twice, "w")).containsExactly("w: search attribute 'region' is declared more than once");
        assertThat(SearchAttributeRules.violations(null, "w")).isEmpty();
        assertThat(SearchAttributeRules.declarations(null)).isEmpty();
    }

    @Test
    void refusesOverlongExpressionsAndNonStringValues() {
        var declared = new LinkedHashMap<String, Object>();
        declared.put("long", "${" + "x".repeat(SearchAttributeRules.MAX_EXPRESSION_LENGTH) + "}");
        declared.put("typed", Map.of("value", 3));
        assertThat(SearchAttributeRules.violations(declared, "w")).hasSize(2);
        assertThat(SearchAttributeRules.isValidName("a".repeat(65))).isFalse();
        assertThat(SearchAttributeRules.isValidName(null)).isFalse();
    }
}
