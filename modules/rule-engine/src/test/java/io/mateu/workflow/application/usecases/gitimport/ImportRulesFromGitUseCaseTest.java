package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Importing the rule catalogue from Git, against a real repository.
 *
 * <p>A local one, created and committed to by the test and cloned over a {@code file:} URI, because
 * the parts worth checking are the ones that only exist once there are files on disk: which of them
 * are treated as rules at all, what a single bad file costs, and what happens to a rule whose file
 * somebody deleted.
 *
 * <p>The scan deliberately does not import everything it finds. A repository holding rules will
 * also hold a README, a CI config and whatever else, so a JSON or YAML file is a rule only if it
 * carries a {@code name} and a {@code type} that names one of the two kinds. Anything else is
 * passed over in silence — not an error, because it was never claiming to be a rule.
 */
class ImportRulesFromGitUseCaseTest {

    private RuleRepository rules;
    private SaveRuleUseCase saveRule;
    private RuleCatalogMetrics metrics;
    private InMemoryImportedDefinitionsRegistry registry;
    private ImportRulesFromGitUseCase useCase;

    @BeforeEach
    void setUp() {
        rules = mock(RuleRepository.class);
        saveRule = mock(SaveRuleUseCase.class);
        metrics = mock(RuleCatalogMetrics.class);
        registry = new InMemoryImportedDefinitionsRegistry();
        useCase = new ImportRulesFromGitUseCase(
                new RuleGitImportProperties(), saveRule, rules, metrics, registry);
        // The save use case is what assigns and returns the id; here it echoes what it was given.
        when(saveRule.handle(any())).thenAnswer(call ->
                ((SaveRuleCommand) call.getArgument(0)).rule().id());
    }

    @Test
    void every_rule_in_the_repository_is_imported(@TempDir Path repo) throws Exception {
        write(repo, "discount.json", rule("discount", "Discount"));
        write(repo, "nested/shipping.yaml", """
                id: shipping
                name: Shipping
                type: expression
                when: "true"
                """);
        commit(repo);

        var result = useCase.handle(List.of(repository(repo)));

        assertThat(result.imported()).containsExactlyInAnyOrder("Discount [discount]", "Shipping [shipping]");
        assertThat(result.errors()).isEmpty();
        // Both formats, and a rule that is not at the top level — a catalogue is a directory tree,
        // not a flat folder.
        var saved = ArgumentCaptor.forClass(SaveRuleCommand.class);
        verify(saveRule, org.mockito.Mockito.times(2)).handle(saved.capture());
        assertThat(saved.getAllValues()).extracting(c -> c.rule().id())
                .containsExactlyInAnyOrder("discount", "shipping");
        verify(metrics).rulesImported(2);
    }

    @Test
    void a_json_file_that_is_not_a_rule_is_passed_over_rather_than_reported(@TempDir Path repo)
            throws Exception {
        write(repo, "rule.json", rule("discount", "Discount"));
        write(repo, "package.json", "{\"name\": \"something\", \"version\": \"1.0.0\"}");
        write(repo, "config.yaml", "server:\n  port: 8080\n");
        commit(repo);

        var result = useCase.handle(List.of(repository(repo)));

        // Only the rule. The other two are neither imported nor errors: they never claimed to be
        // rules, and a repository full of "errors" for its own README teaches people to ignore the
        // list.
        assertThat(result.imported()).containsExactly("Discount [discount]");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void one_unreadable_file_costs_only_itself(@TempDir Path repo) throws Exception {
        write(repo, "good.json", rule("discount", "Discount"));
        write(repo, "broken.json", "{ this is not json");
        commit(repo);

        var result = useCase.handle(List.of(repository(repo)));

        assertThat(result.imported()).containsExactly("Discount [discount]");
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst()).contains("broken.json");
    }

    @Test
    void a_repository_that_cannot_be_cloned_is_an_error_and_not_a_crash(@TempDir Path nowhere) {
        var missing = new RuleGitImportProperties.GitRepository();
        missing.setUrl(nowhere.resolve("does-not-exist").toUri().toString());

        var result = useCase.handle(List.of(missing));

        assertThat(result.imported()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst()).contains("Repository");
        verify(saveRule, never()).handle(any());
    }

    @Test
    void a_rule_whose_file_was_deleted_is_pruned_on_the_next_import(@TempDir Path repo)
            throws Exception {
        write(repo, "discount.json", rule("discount", "Discount"));
        write(repo, "shipping.json", rule("shipping", "Shipping"));
        commit(repo);
        useCase.handle(List.of(repository(repo)));

        Files.delete(repo.resolve("shipping.json"));
        commit(repo);
        when(rules.findById("shipping")).thenReturn(
                java.util.Optional.of(rule("shipping")));

        var result = useCase.handle(List.of(repository(repo)));

        // The catalogue follows the repository: a rule removed there stops being served here, or
        // the two drift and nobody can say which is the source of truth.
        assertThat(result.pruned()).containsExactly("Rule shipping [shipping]");
        assertThat(result.imported()).containsExactly("Discount [discount]");
    }

    // ── the repository under test ─────────────────────────────────────────────────────────────

    private static RuleGitImportProperties.GitRepository repository(Path repo) {
        var configured = new RuleGitImportProperties.GitRepository();
        configured.setUrl(repo.toUri().toString());
        configured.setBranch("main");
        return configured;
    }

    private static void write(Path repo, String name, String content) throws IOException {
        var file = repo.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Commits whatever is in the working tree, on `main`. */
    private static void commit(Path repo) throws Exception {
        try (var git = Files.exists(repo.resolve(".git"))
                ? Git.open(repo.toFile())
                : Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            git.commit().setMessage("rules").setSign(false)
                    .setAuthor("test", "test@example.com").call();
        }
    }

    private static String rule(String id, String name) {
        return """
                {
                  "id": "%s",
                  "name": "%s",
                  "type": "expression",
                  "when": "true"
                }
                """.formatted(id, name);
    }

    private static Rule rule(String id) {
        return new Rule(id, "Rule " + id, "", io.mateu.workflow.domain.RuleType.EXPRESSION,
                1, 0, List.of(), "true", List.of(), null, null, null, null);
    }
}
