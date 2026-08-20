package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
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
 * {@code .ecrule}, which was a ghost: declared in {@code DerivedIds} and in the Maven plugin's copy
 * of that list, and read by nothing.
 *
 * <p>The state that produced these tests is the one worth naming — the build validated a file the
 * engine would then not load. Green light for something that does not work is worse than either
 * refusing the extension or supporting it, which is why this went the way it did.
 */
class RulesEcruleExtensionTest {

    private final SaveRuleUseCase saveRule = mock(SaveRuleUseCase.class);
    private final InMemoryImportedDefinitionsRegistry registry = new InMemoryImportedDefinitionsRegistry();
    private final ImportRulesFromDirectoryUseCase directoryImport = new ImportRulesFromDirectoryUseCase(
            new RuleDirectoryImportProperties(), saveRule, mock(RuleRepository.class),
            mock(RuleCatalogMetrics.class), registry);
    private final ImportRulesFromGitUseCase gitImport = new ImportRulesFromGitUseCase(
            new RuleGitImportProperties(), mock(RuleCatalogMetrics.class), directoryImport);

    RulesEcruleExtensionTest() {
        when(saveRule.handle(any()))
                .thenAnswer(call -> ((SaveRuleCommand) call.getArgument(0)).rule().id());
    }

    private static final String A_RULE = """
            id: %s
            name: %s
            type: expression
            when: "order.total > 100"
            then:
              - name: discount
                expression: "order.total * 0.1"
            """;

    @Test
    void anEcruleIsImportedFromAClonedRepository(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("discount.ecrule"), A_RULE.formatted("discount", "Discount"));
        commit(repo);

        var configured = new RuleGitImportProperties.GitRepository();
        configured.setUrl(repo.toUri().toString());
        configured.setBranch("main");

        var result = gitImport.handle(List.of(configured));

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).containsExactly("Discount [discount]");
    }

    /**
     * Counting imported rules would not have caught this: the old filter skipped the file, the
     * import reported no error, and the count was simply zero — the same zero as a repository with
     * no rules in it. The assertion has to name the rule.
     */
    @Test
    void anEcruleIsImportedFromADirectory(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("pricing"));
        Files.writeString(dir.resolve("pricing/discount.ecrule"), A_RULE.formatted("discount", "Discount"));

        var result = directoryImport.handle(List.of(dir.toString()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).containsExactly("Discount [discount]");
    }

    @Test
    void theThreeGenericExtensionsStillWork(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.yaml"), A_RULE.formatted("a", "A"));
        Files.writeString(dir.resolve("b.yml"), A_RULE.formatted("b", "B"));
        Files.writeString(dir.resolve("c.json"),
                "{ \"id\": \"c\", \"name\": \"C\", \"type\": \"expression\", \"when\": \"true\" }");

        assertThat(directoryImport.handle(List.of(dir.toString())).imported())
                .containsExactlyInAnyOrder("A [a]", "B [b]", "C [c]");
    }

    /**
     * The filter and the id derivation now agree, which they did not: the import handed its own
     * three-extension filter to {@code DerivedIds.declaredUnder}, whose own list has six. A file the
     * filter skipped could still have had an id derived for it — two lists contradicting each other
     * inside a single call.
     */
    @Test
    void anEcruleWithNoIdGetsOneDerivedFromItsPath(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("pricing"));
        Files.writeString(dir.resolve("pricing/discount.ecrule"), """
                name: Discount
                type: expression
                when: "true"
                """);

        assertThat(directoryImport.handle(List.of(dir.toString())).imported())
                .containsExactly("Discount [pricing.discount]");
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
