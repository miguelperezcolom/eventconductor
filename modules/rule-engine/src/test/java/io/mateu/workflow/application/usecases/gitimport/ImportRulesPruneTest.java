package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
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
    private final ImportRulesFromDirectoryUseCase useCase = new ImportRulesFromDirectoryUseCase(
            mock(RuleDirectoryImportProperties.class), mock(SaveRuleUseCase.class),
            new io.mateu.workflow.application.services.RuleValidator(), ruleRepository,
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

    @Test
    void aRuleWhoseFileFailsIsNotDeletedAndIsPrunedOnceTheSourceImportsCleanly(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var saveRule = mock(SaveRuleUseCase.class);
        when(saveRule.handle(any())).thenAnswer(call ->
                ((io.mateu.workflow.application.usecases.saverule.SaveRuleCommand) call.getArgument(0)).rule().id());
        var importer = new ImportRulesFromDirectoryUseCase(mock(RuleDirectoryImportProperties.class), saveRule,
                new io.mateu.workflow.application.services.RuleValidator(), ruleRepository,
                mock(RuleCatalogMetrics.class), registry);
        var source = dir.toString();
        registry.replace("rule", source, Set.of("a", "b"));
        java.nio.file.Files.writeString(dir.resolve("a.json"), "{ \"id\": \"a\", \"name\": \"A\", \"type\": \"expression\","
                + " \"when\": \"true\", \"then\": [ { \"name\": \"ok\", \"expression\": \"true\" } ] }");
        // "b" is still in the source, it just cannot be read right now.
        java.nio.file.Files.writeString(dir.resolve("b.json"), "{ \"id\": \"b\", \"name\": ");
        var rule = mock(Rule.class);
        when(rule.name()).thenReturn("B");
        when(ruleRepository.findById("b")).thenReturn(Optional.of(rule));

        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var pruned = new ArrayList<String>();
        importer.importFrom(dir, source, imported, errors, pruned);

        verify(ruleRepository, never()).deleteAllById(any());
        assertThat(pruned).isEmpty();
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("Pruning skipped for "));
        assertThat(registry.idsFor("rule", source)).containsExactlyInAnyOrder("a", "b");

        java.nio.file.Files.delete(dir.resolve("b.json"));
        importer.importFrom(dir, source, imported, new ArrayList<>(), pruned);
        verify(ruleRepository).deleteAllById(List.of("b"));
    }
}
