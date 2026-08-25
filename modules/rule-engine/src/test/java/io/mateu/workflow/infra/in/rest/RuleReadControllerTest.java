package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The catalogue's read API — what a remote rule-runtime pulls when it runs with
 * {@code rules.source=rest}.
 *
 * <p>Small enough to look self-evident, which is why it went untested, and it carries two decisions
 * that are not. A missing rule is a 404 rather than a 200 with an empty body, because a runtime
 * that treats "no rule" as "a rule that matches nothing" would evaluate a step to false instead of
 * failing it. And the tag filter is applied here rather than in the query, so a blank tag has to
 * mean "everything" — a runtime asking for an untagged set otherwise gets nothing at all.
 */
class RuleReadControllerTest {

    private RuleRepository rules;
    private RuleReadController controller;

    @BeforeEach
    void setUp() {
        rules = mock(RuleRepository.class);
        controller = new RuleReadController(rules);
    }

    @Test
    void without_a_tag_it_lists_the_whole_catalogue() {
        when(rules.findAll()).thenReturn(List.of(
                rule("r1", List.of("pricing")), rule("r2", List.of()), rule("r3", null)));

        assertThat(controller.list(null)).extracting(Rule::id)
                .containsExactly("r1", "r2", "r3");
    }

    @Test
    void a_blank_tag_is_not_a_filter() {
        // "" arrives from a query string that names the parameter and gives it nothing. Treating it
        // as a real tag would answer an empty catalogue, which reads as "this engine has no rules".
        when(rules.findAll()).thenReturn(List.of(rule("r1", List.of("pricing"))));

        assertThat(controller.list("")).hasSize(1);
        assertThat(controller.list("   ")).hasSize(1);
    }

    @Test
    void a_tag_narrows_the_catalogue_to_the_rules_carrying_it() {
        when(rules.findAll()).thenReturn(List.of(
                rule("r1", List.of("pricing", "eu")),
                rule("r2", List.of("shipping")),
                rule("r3", null)));

        assertThat(controller.list("pricing")).extracting(Rule::id).containsExactly("r1");
        assertThat(controller.list("shipping")).extracting(Rule::id).containsExactly("r2");
        // A rule with no tags at all matches no tag, and must not blow up on the null.
        assertThat(controller.list("none")).isEmpty();
    }

    @Test
    void a_rule_that_exists_comes_back_with_200() {
        when(rules.findById("r1")).thenReturn(Optional.of(rule("r1", List.of())));

        var response = controller.get("r1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo("r1");
    }

    @Test
    void a_rule_that_does_not_exist_is_a_404_and_not_an_empty_rule() {
        when(rules.findById("missing")).thenReturn(Optional.empty());

        var response = controller.get("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    private static Rule rule(String id, List<String> tags) {
        return new Rule(id, "Rule " + id, "", RuleType.EXPRESSION, 1, 0, tags,
                "true", List.of(), null, null, null, null);
    }
}
