package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Scoping the scan to a subdirectory of the clone.
 *
 * <p>RuleGitImportProperties.GitRepository had no `directory` field, while the workflow engine's copy
 * of the same class did — so `RULES_GITIMPORT_REPOSITORIES_0_DIRECTORY` was accepted by relaxed
 * binding and then did nothing. Pointed at a repository that is not exclusively definitions, the
 * import walked the entire clone: a parse error per unrelated YAML file, and anything that happened
 * to look like a definition imported as one.
 *
 * <p>The escape guard is the part worth more than its size. The directory comes from configuration,
 * and the root it is resolved against is the throwaway clone the import deletes afterwards, so a
 * `directory: ../..` that normalised instead of being refused would walk — and prune against —
 * whatever it found outside.
 */
class RulesGitImportScanTest {

    private final ImportRulesFromGitUseCase useCase = new ImportRulesFromGitUseCase(
            new RuleGitImportProperties(), mock(io.mateu.workflow.application.out.RuleCatalogMetrics.class),
            new ImportRulesFromDirectoryUseCase(new RuleDirectoryImportProperties(),
                    mock(io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase.class),
                    new io.mateu.workflow.application.services.RuleValidator(),
                    mock(io.mateu.workflow.application.out.RuleRepository.class),
                    mock(io.mateu.workflow.application.out.RuleCatalogMetrics.class),
                    new io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry()));

    private static RuleGitImportProperties.GitRepository repo(String directory) {
        var repo = new RuleGitImportProperties.GitRepository();
        repo.setUrl("https://github.com/org/defs.git");
        repo.setDirectory(directory);
        return repo;
    }

    @Test
    void noDirectoryMeansScanTheWholeRepository(@TempDir Path root) throws IOException {
        assertThat(useCase.resolveScanRoot(repo(null), root)).isEqualTo(root);
        assertThat(useCase.resolveScanRoot(repo("   "), root)).isEqualTo(root);
    }

    @Test
    void aDeclaredSubdirectoryNarrowsTheScan(@TempDir Path root) throws IOException {
        var definitions = Files.createDirectories(root.resolve("definitions"));

        assertThat(useCase.resolveScanRoot(repo("definitions"), root)).isEqualTo(definitions);
    }

    @Test
    void aDirectoryThatClimbsOutOfTheRepositoryIsRefused(@TempDir Path root) {
        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("../.."), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the repository root");

        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("definitions/../../.."), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the repository root");
    }

    @Test
    void aDirectoryThatIsNotThereIsAnErrorRatherThanAnEmptyImport(@TempDir Path root) {
        // Silently importing nothing would read as "the repository has no definitions", which is
        // the same thing a typo in the mount path produces.
        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("nope"), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found in repository");
    }

    @Test
    void twoEntriesOnOneRepositoryPruneIndependently() {
        // Same URL, different subdirectories: folding the directory into the prune key is what
        // stops one of them pruning the other's definitions on every import.
        assertThat(ImportRulesFromGitUseCase.pruneKey(repo(null)))
                .isEqualTo("https://github.com/org/defs.git");
        assertThat(ImportRulesFromGitUseCase.pruneKey(repo("a")))
                .isNotEqualTo(ImportRulesFromGitUseCase.pruneKey(repo("b")));
    }

    /**
     * The helpers above are only worth having if the import calls them. This is the wiring the bug
     * actually was — and it is checked against a real repository, because for rules there is no
     * delegate to observe: the scan is inline, so the only way to see what it walked is what it
     * imported.
     */
    @Test
    void theImportScansOnlyTheConfiguredSubdirectory(@TempDir Path repoDir) throws Exception {
        var saveRule = mock(io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase.class);
        org.mockito.Mockito.when(saveRule.handle(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((io.mateu.workflow.application.usecases.saverule.SaveRuleCommand)
                        call.getArgument(0)).rule().id());
        var scoped = new ImportRulesFromGitUseCase(new RuleGitImportProperties(),
                mock(io.mateu.workflow.application.out.RuleCatalogMetrics.class),
                new ImportRulesFromDirectoryUseCase(new RuleDirectoryImportProperties(), saveRule,
                        new io.mateu.workflow.application.services.RuleValidator(),
                        mock(io.mateu.workflow.application.out.RuleRepository.class),
                        mock(io.mateu.workflow.application.out.RuleCatalogMetrics.class),
                        new io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry()));

        Files.createDirectories(repoDir.resolve("rules"));
        Files.writeString(repoDir.resolve("rules/wanted.json"), rule("wanted", "Wanted"));
        // A rule-shaped file outside the configured directory — a chart template, a fixture,
        // anything. Walking the whole clone imports it; scoping the scan does not.
        Files.writeString(repoDir.resolve("stray.json"), rule("stray", "Stray"));
        commit(repoDir);

        var configured = new RuleGitImportProperties.GitRepository();
        configured.setUrl(repoDir.toUri().toString());
        configured.setBranch("main");
        configured.setDirectory("rules");

        var result = scoped.handle(java.util.List.of(configured));

        assertThat(result.imported()).containsExactly("Wanted [wanted]");
        assertThat(result.errors()).isEmpty();
    }

    private static String rule(String id, String name) {
        return """
                { "id": "%s", "name": "%s", "type": "expression", "when": "true", "then": [ { "name": "ok", "expression": "true" } ] }
                """.formatted(id, name);
    }

    private static void commit(Path repo) throws Exception {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(repo.toFile())
                .setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("rules").setSign(false)
                    .setAuthor("test", "test@example.com").call();
        }
    }
}
