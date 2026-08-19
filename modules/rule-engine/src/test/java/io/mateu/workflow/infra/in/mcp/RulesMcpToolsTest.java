package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.application.services.RuleValidator;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleCommand;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleUseCase;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleEvaluationResult;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule catalogue as an agent sees it.
 *
 * <p>These are the six tools an MCP client can call, and they were entirely untested — which
 * matters more here than for an ordinary adapter, because the caller is a language model. It cannot
 * read a stack trace, and it will happily carry on with whatever string it is handed. So what each
 * tool does when things go wrong is part of its contract, and the three behaviours below are not
 * the same and must not drift into each other:
 *
 * <ul>
 *   <li>{@code getRule} on an unknown id <b>throws</b> — the caller asked for something that is not
 *       there, and returning "" would read as an empty rule.</li>
 *   <li>{@code validateRule} <b>returns</b> the violation as its answer, because being told why a
 *       definition is invalid is the whole point of asking.</li>
 *   <li>{@code evaluateRule} <b>returns</b> the failure as text too, prefixed so it cannot be
 *       mistaken for a result.</li>
 * </ul>
 */
class RulesMcpToolsTest {

    private RuleRepository rules;
    private RuleValidator validator;
    private RuleEvaluator evaluator;
    private RuleJsonMapper mapper;
    private SaveRuleUseCase saveRule;
    private DeleteRuleUseCase deleteRule;
    private RulesMcpTools tools;

    @BeforeEach
    void setUp() {
        rules = mock(RuleRepository.class);
        validator = mock(RuleValidator.class);
        evaluator = mock(RuleEvaluator.class);
        mapper = mock(RuleJsonMapper.class);
        saveRule = mock(SaveRuleUseCase.class);
        deleteRule = mock(DeleteRuleUseCase.class);
        tools = new RulesMcpTools(rules, validator, evaluator, mapper, saveRule, deleteRule,
                mock(ImportRulesFromGitUseCase.class));
    }

    @Test
    void the_listing_summarises_each_rule_without_its_body() {
        when(rules.findAll()).thenReturn(List.of(
                rule("r1", RuleType.EXPRESSION, List.of("pricing")),
                rule("r2", null, null)));

        var listed = tools.listRules();

        assertThat(listed).hasSize(2);
        assertThat(listed.getFirst().id()).isEqualTo("r1");
        assertThat(listed.getFirst().type()).isEqualTo("expression");
        assertThat(listed.getFirst().tags()).containsExactly("pricing");
        // A rule with no type must not take the listing down with it: the summary is what an agent
        // reads before it knows anything, so it has to survive a half-written catalogue.
        assertThat(listed.get(1).type()).isNull();
    }

    @Test
    void getting_a_rule_returns_its_canonical_json() {
        var rule = rule("r1", RuleType.EXPRESSION, List.of());
        when(rules.findById("r1")).thenReturn(Optional.of(rule));
        when(mapper.toJson(rule)).thenReturn("{\"id\":\"r1\"}");

        assertThat(tools.getRule("r1")).isEqualTo("{\"id\":\"r1\"}");
    }

    @Test
    void getting_a_rule_that_is_not_there_says_so_rather_than_returning_nothing() {
        when(rules.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.getRule("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void saving_parses_the_definition_and_returns_the_id_the_use_case_gives_back() {
        var rule = rule("r1", RuleType.EXPRESSION, List.of());
        when(mapper.toRule("yaml or json")).thenReturn(rule);
        when(saveRule.handle(any())).thenReturn("r1");

        assertThat(tools.saveRule("yaml or json")).isEqualTo("r1");

        var captor = ArgumentCaptor.forClass(SaveRuleCommand.class);
        verify(saveRule).handle(captor.capture());
        assertThat(captor.getValue().rule()).isSameAs(rule);
    }

    @Test
    void validating_a_good_definition_answers_valid() {
        when(mapper.toRule(anyString())).thenReturn(rule("r1", RuleType.EXPRESSION, List.of()));

        assertThat(tools.validateRule("{}")).isEqualTo("valid");
    }

    @Test
    void validating_a_bad_definition_answers_with_the_reason_rather_than_throwing() {
        when(mapper.toRule(anyString())).thenReturn(rule("r1", RuleType.EXPRESSION, List.of()));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("when must not be empty"))
                .when(validator).validate(any());

        assertThat(tools.validateRule("{}")).isEqualTo("when must not be empty");
    }

    @Test
    void deleting_asks_the_use_case_and_confirms() {
        assertThat(tools.deleteRule("r1")).isEqualTo("deleted");

        var captor = ArgumentCaptor.forClass(DeleteRuleCommand.class);
        verify(deleteRule).handle(captor.capture());
        assertThat(captor.getValue().ruleId()).isEqualTo("r1");
    }

    @Test
    void evaluating_passes_the_facts_through_as_a_map_and_returns_the_result_as_json() {
        when(evaluator.evaluate(org.mockito.ArgumentMatchers.eq("r1"), any()))
                .thenReturn(new RuleEvaluationResult(true, Map.of("discount", 10), List.of()));

        var answer = tools.evaluateRule("r1", "{\"order\": {\"total\": 200}}");

        assertThat(answer).contains("discount").contains("10").contains("\"matched\":true");
    }

    @Test
    void evaluating_with_facts_that_are_not_json_answers_the_failure_rather_than_throwing() {
        // An agent hands this tool whatever it composed. A thrown exception would surface as a
        // transport error with no hint; the prefix makes the answer unmistakably not a result.
        var answer = tools.evaluateRule("r1", "not json");

        assertThat(answer).startsWith("Evaluation failed:");
    }

    @Test
    void the_system_context_tells_an_agent_what_the_catalogue_is() {
        assertThat(tools.getSystemContext())
                .contains("expression")
                .contains("decision-table");
    }

    private static Rule rule(String id, RuleType type, List<String> tags) {
        return new Rule(id, "Rule " + id, "", type, 1, 0, tags,
                "true", List.of(), null, null, null, null);
    }
}
