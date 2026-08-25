package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The id a rule file gets when it declares none.
 *
 * <p>{@link SaveRuleUseCase} generates one, which is right when a person saves a rule in the UI and
 * wrong for a file: a fresh id per import leaves the rule the previous import created sitting there,
 * reconcilable with nothing and prunable by nothing, and every push through the git webhook adds
 * another copy of it.
 */
class RulesImportIdsTest {

    private final SaveRuleUseCase saveRule = mock(SaveRuleUseCase.class);
    private final InMemoryImportedDefinitionsRegistry registry = new InMemoryImportedDefinitionsRegistry();
    private final ImportRulesFromGitUseCase useCase = new ImportRulesFromGitUseCase(
            new RuleGitImportProperties(), mock(RuleCatalogMetrics.class),
            new ImportRulesFromDirectoryUseCase(new RuleDirectoryImportProperties(), saveRule,
                    new io.mateu.workflow.application.services.RuleValidator(),
                    mock(RuleRepository.class), mock(RuleCatalogMetrics.class), registry));

    RulesImportIdsTest() {
        // The real one returns the id it saved under; the id is what this test is about.
        when(saveRule.handle(any()))
                .thenAnswer(call -> ((SaveRuleCommand) call.getArgument(0)).rule().id());
    }

    @Test
    void aRuleWithNoIdKeepsTheSameIdOnEveryImport(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("pricing"));
        Files.writeString(repo.resolve("pricing/discount.json"),
                "{ \"name\": \"Discount\", \"type\": \"expression\", \"when\": \"true\", \"then\": [ { \"name\": \"ok\", \"expression\": \"true\" } ] }");
        commit(repo);

        var first = useCase.handle(List.of(repository(repo)));
        var second = useCase.handle(List.of(repository(repo)));

        assertThat(first.imported()).containsExactly("Discount [pricing.discount]");
        assertThat(second.imported()).containsExactly("Discount [pricing.discount]");
        // Prune-tracked, which a generated id could never be: nothing connected it to the file.
        assertThat(registry.idsFor("rule", repo.toUri().toString()))
                .containsExactly("pricing.discount");
    }

    @Test
    void aDeclaredIdStillWins(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("discount.json"),
                "{ \"id\": \"the-discount\", \"name\": \"Discount\", \"type\": \"expression\","
                        + " \"when\": \"true\", \"then\": [ { \"name\": \"ok\", \"expression\": \"true\" } ] }");
        commit(repo);

        assertThat(useCase.handle(List.of(repository(repo))).imported())
                .containsExactly("Discount [the-discount]");
    }

    @Test
    void aPathDerivedIdNeverTakesOneAnotherRuleDeclares(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("elsewhere.json"),
                "{ \"id\": \"pricing.discount\", \"name\": \"Elsewhere\", \"type\": \"expression\","
                        + " \"when\": \"true\", \"then\": [ { \"name\": \"ok\", \"expression\": \"true\" } ] }");
        Files.createDirectories(repo.resolve("pricing"));
        Files.writeString(repo.resolve("pricing/discount.json"),
                "{ \"name\": \"Discount\", \"type\": \"expression\", \"when\": \"true\", \"then\": [ { \"name\": \"ok\", \"expression\": \"true\" } ] }");
        commit(repo);

        var result = useCase.handle(List.of(repository(repo)));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.imported()).containsExactly("Elsewhere [pricing.discount]");
    }

    private static RuleGitImportProperties.GitRepository repository(Path repo) {
        var configured = new RuleGitImportProperties.GitRepository();
        configured.setUrl(repo.toUri().toString());
        configured.setBranch("main");
        return configured;
    }

    private static void commit(Path repo) throws Exception {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(repo.toFile())
                .setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("rules").setSign(false)
                    .setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        }
    }
}
