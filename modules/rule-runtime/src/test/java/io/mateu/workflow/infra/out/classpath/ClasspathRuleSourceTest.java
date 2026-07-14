package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathRuleSourceTest {

    private final ClasspathRuleSource source = new ClasspathRuleSource(new RuleJsonMapper());

    @Test
    void loadsJsonAndYamlRulesFromClasspath() {
        assertThat(source.findAll()).hasSize(2);
        assertThat(source.findById("high-value-order")).hasValueSatisfying(rule ->
                assertThat(rule.type()).isEqualTo(RuleType.EXPRESSION));
        assertThat(source.findById("shipping-costs")).hasValueSatisfying(rule ->
                assertThat(rule.type()).isEqualTo(RuleType.DECISION_TABLE));
    }

    @Test
    void unknownRuleIsEmpty() {
        assertThat(source.findById("nope")).isEmpty();
    }
}
