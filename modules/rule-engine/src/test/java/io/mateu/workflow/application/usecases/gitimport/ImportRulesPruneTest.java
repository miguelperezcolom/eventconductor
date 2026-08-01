package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImportRulesPruneTest {

    private final RuleRepository ruleRepository = mock(RuleRepository.class);
    private final InMemoryImportedDefinitionsRegistry registry = new InMemoryImportedDefinitionsRegistry();
    private final ImportRulesFromGitUseCase useCase = new ImportRulesFromGitUseCase(
            mock(RuleGitImportProperties.class), mock(SaveRuleUseCase.class), ruleRepository,
            mock(RuleCatalogMetrics.class), registry);

    private static final String REPO = "https://github.com/org/rules.git";

    @Test
    void deletesRulesRemovedFromTheRepo() {
        registry.replace("rule", REPO, Set.of("a", "b"));
        var rule = mock(Rule.class);
        when(rule.name()).thenReturn("B");
        when(ruleRepository.findById("b")).thenReturn(Optional.of(rule));

        var pruned = new ArrayList<String>();
        useCase.pruneRemovedRules(REPO, Set.of("a"), pruned); // "b" is gone

        verify(ruleRepository).deleteAllById(List.of("b"));
        assertThat(pruned).hasSize(1);
        assertThat(registry.idsFor("rule", REPO)).containsExactly("a");
    }

    @Test
    void doesNothingWhenEverythingStillPresent() {
        registry.replace("rule", REPO, Set.of("a", "b"));
        var pruned = new ArrayList<String>();
        useCase.pruneRemovedRules(REPO, Set.of("a", "b"), pruned);
        verify(ruleRepository, never()).deleteAllById(any());
        assertThat(pruned).isEmpty();
    }
}
