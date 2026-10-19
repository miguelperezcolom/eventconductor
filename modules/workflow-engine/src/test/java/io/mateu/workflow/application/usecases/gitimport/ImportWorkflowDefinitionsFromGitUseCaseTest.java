package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.services.CronScheduleImporter;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository;
import io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The git import end to end against a real repository: a bare repo in a temp directory cloned over
 * {@code file://}, so what is pinned is what a user sees when they push — first import, re-import,
 * removal, a broken file, a subdirectory, and a URL whose credentials are never recorded.
 */
class ImportWorkflowDefinitionsFromGitUseCaseTest {

    @TempDir
    Path tmp;

    private LocalGitRepo git;
    private ClasspathWorkflowDefinitionRepository definitions;
    private InMemoryImportedDefinitionsRegistry registry;
    private InMemoryCronScheduleRepository schedules;
    private GitImportProperties properties;
    private ImportWorkflowDefinitionsFromGitUseCase useCase;

    private static String definition(String id, String name) {
        return (id == null ? "" : "id: " + id + "\n") + """
                name: %s
                version: 1
                steps:
                  - id: start
                    type: START
                    name: Start
                """.formatted(name);
    }

    @BeforeEach
    void setUp() throws Exception {
        git = new LocalGitRepo(tmp);
        var resolver = new TaskReferenceResolver(new ClasspathTaskContractRepository());
        definitions = new ClasspathWorkflowDefinitionRepository(resolver);
        registry = new InMemoryImportedDefinitionsRegistry();
        schedules = new InMemoryCronScheduleRepository();
        var directoryImport = new ImportWorkflowDefinitionsFromDirectoryUseCase(mock(DirectoryImportProperties.class),
                definitions, registry, new WorkflowDefinitionValidator(), resolver,
                new CronScheduleImporter(definitions, schedules, new InMemorySchedulePauseRepository()));
        properties = new GitImportProperties();
        useCase = new ImportWorkflowDefinitionsFromGitUseCase(properties, directoryImport);
    }

    @AfterEach
    void tearDown() throws Exception {
        git.close();
    }

    private GitImportProperties.GitRepository repo(String url, String directory) {
        var repo = new GitImportProperties.GitRepository();
        repo.setUrl(url);
        repo.setDirectory(directory);
        properties.getRepositories().add(repo);
        return repo;
    }

    private static Map<String, String> files(String... pathsAndContents) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            map.put(pathsAndContents[i], pathsAndContents[i + 1]);
        }
        return map;
    }

    @Test
    void aFirstImportLoadsEveryDefinitionAndTheCronFileUnderTheRepositoryUrl() throws Exception {
        git.push(files(
                "flows/order.yaml", definition("order", "Order"),
                "flows/invoice.yaml", definition(null, "Invoice"),
                "cron.yaml", "- id: nightly\n  cron: \"@daily\"\n  workflow: order\n",
                "README.md", "not a definition"));
        repo(git.url(), null);

        var result = useCase.handle();

        assertThat(result.errors()).isEmpty();
        assertThat(result.pruned()).isEmpty();
        assertThat(result.imported()).hasSize(2).anyMatch(s -> s.startsWith("Order [order]"));
        assertThat(definitions.findById("order")).map(WorkflowDefinition::name).contains("Order");
        // A file without an id gets one derived from its path, stable across imports.
        var derived = registry.idsFor("workflow", git.url());
        assertThat(derived).hasSize(2).contains("order");
        assertThat(schedules.findAll()).singleElement()
                .satisfies(s -> {
                    assertThat(s.source()).isEqualTo(git.url());
                    assertThat(s.workflowId()).isEqualTo("order");
                });
    }

    @Test
    void reImportingTheSameCommitChangesNothingAndDuplicatesNothing() throws Exception {
        git.push(files("order.yaml", definition("order", "Order"), "invoice.yaml", definition(null, "Invoice")));
        repo(git.url(), null);

        var first = useCase.handle();
        var countAfterFirst = definitions.findAll().size();
        var second = useCase.handle();

        assertThat(second.errors()).isEmpty();
        assertThat(second.pruned()).isEmpty();
        assertThat(second.imported()).containsExactlyInAnyOrderElementsOf(first.imported());
        assertThat(definitions.findAll()).hasSize(countAfterFirst);
    }

    @Test
    void aReImportPicksUpAnEditedDefinition() throws Exception {
        git.push(files("order.yaml", definition("order", "Order")));
        repo(git.url(), null);
        useCase.handle();

        git.push(files("order.yaml", definition("order", "Order v2")));
        useCase.handle();

        assertThat(definitions.findById("order")).map(WorkflowDefinition::name).contains("Order v2");
    }

    @Test
    void aDefinitionRemovedFromTheRepositoryIsArchivedOnTheNextImportAndTheOthersAreLeftAlone() throws Exception {
        git.push(files("order.yaml", definition("order", "Order"), "invoice.yaml", definition("invoice", "Invoice"),
                "cron.yaml", "- id: nightly\n  cron: \"@daily\"\n  workflow: invoice\n"));
        repo(git.url(), null);
        useCase.handle();

        git.push(files("invoice.yaml", null, "cron.yaml", null));
        var result = useCase.handle();

        assertThat(result.pruned()).containsExactly("Invoice [invoice]");
        assertThat(definitions.findById("invoice")).map(WorkflowDefinition::archived).contains(true);
        assertThat(definitions.findById("order")).map(WorkflowDefinition::archived).contains(false);
        // Deleting the cron file stops its schedules.
        assertThat(schedules.findAll()).isEmpty();

        // And pruning is not repeated: the next import has nothing left to archive.
        assertThat(useCase.handle().pruned()).isEmpty();
    }

    @Test
    void aBrokenFileIsReportedByNameWithoutStoppingTheRestOfTheImport() throws Exception {
        git.push(files("order.yaml", definition("order", "Order"),
                "broken.yaml", "name: Broken\nsteps: [ : : not yaml"));
        repo(git.url(), null);

        var result = useCase.handle();

        assertThat(result.errors()).singleElement().asString().startsWith("File broken.yaml");
        assertThat(definitions.findById("order")).isPresent();
    }

    @Test
    void onlyTheConfiguredSubdirectoryIsScannedAndItIsPartOfTheSourceKey() throws Exception {
        git.push(files("flows/order.yaml", definition("order", "Order"), "other/invoice.yaml", definition("invoice", "Invoice")));
        repo(git.url(), "flows");

        var result = useCase.handle();

        assertThat(result.errors()).isEmpty();
        assertThat(definitions.findById("order")).isPresent();
        assertThat(definitions.findById("invoice")).isEmpty();
        assertThat(registry.idsFor("workflow", git.url() + "#flows")).containsExactly("order");
        assertThat(registry.idsFor("workflow", git.url())).isEmpty();
    }

    @Test
    void aMissingSubdirectoryIsAnErrorForThatRepositoryAndOtherRepositoriesStillImport() throws Exception {
        git.push(files("order.yaml", definition("order", "Order")));
        repo(git.url(), "nowhere");
        repo(git.url(), "../../etc");
        repo(git.url(), null);

        var result = useCase.handle();

        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0)).contains("directory 'nowhere' not found in repository");
        assertThat(result.errors().get(1)).contains("escapes the repository root");
        assertThat(definitions.findById("order")).isPresent();
    }

    @Test
    void anUnknownBranchIsReportedAsAnErrorAndImportsNothing() throws Exception {
        git.push(files("order.yaml", definition("order", "Order")));
        repo(git.url(), null).setBranch("does-not-exist");

        var result = useCase.handle();

        assertThat(result.errors()).singleElement().asString().startsWith("Repository " + git.url());
        assertThat(result.imported()).isEmpty();
        assertThat(definitions.findById("order")).isEmpty();
    }

    @Test
    void theCloneIsRemovedAfterTheImport() throws Exception {
        git.push(files("order.yaml", definition("order", "Order")));
        repo(git.url(), null);
        // Clones go to a directory of this test's own: the shared system temp directory is also used by
        // other builds on the machine, whose clones come and go while this one runs.
        var clones = Files.createDirectories(tmp.resolve("clones"));
        useCase.cloneRoot = clones;

        useCase.handle();

        assertThat(definitions.findById("order")).isPresent();
        try (var s = Files.list(clones)) {
            assertThat(s).as("the clone made for the import is deleted").isEmpty();
        }
    }

    @Test
    void aRepositoryThatCannotBeClonedIsReportedWithoutTheCredentialsInItsUrl() {
        // Nothing listens on port 1 of the loopback: the clone fails without leaving the machine.
        repo("https://ci-bot:fake-secret-token@127.0.0.1:1/org/defs.git", null);
        // A token used as the user name, as GitHub accepts it.
        repo("https://fake-secret-token@127.0.0.1:1/org/defs.git", null);

        var result = useCase.handle();

        assertThat(result.errors()).hasSize(2).allSatisfy(error -> assertThat(error)
                .startsWith("Repository https://******@127.0.0.1:1/org/defs.git")
                .doesNotContain("fake-secret-token")
                .doesNotContain("ci-bot"));
    }

    @Test
    void configuredCredentialsAreUsedForTheCloneButNeverRecordedInTheSourceKey() throws Exception {
        git.push(files("order.yaml", definition("order", "Order"),
                "cron.yaml", "- id: nightly\n  cron: \"@daily\"\n  workflow: order\n"));
        var repo = repo(git.url(), null);
        repo.setUsername("ci-bot");
        repo.setPassword("fake-secret-token");

        var result = useCase.handle();

        assertThat(result.errors()).isEmpty();
        assertThat(registry.idsFor("workflow", git.url())).containsExactly("order");
        assertThat(schedules.findAll()).allSatisfy(s -> assertThat(s.source())
                .isEqualTo(git.url()).doesNotContain("fake-secret-token"));
    }

    @Test
    void aSubsetCanBeReImportedWithoutTouchingTheOtherConfiguredRepositories() throws Exception {
        git.push(files("order.yaml", definition("order", "Order")));
        var other = new LocalGitRepo(tmp.resolve("other"));
        try {
            other.push(files("invoice.yaml", definition("invoice", "Invoice")));
            repo(git.url(), null);
            var second = repo(other.url(), null);

            var result = useCase.handle(List.of(second));

            assertThat(result.imported()).containsExactly("Invoice [invoice]");
            assertThat(definitions.findById("order")).isEmpty();
        } finally {
            other.close();
        }
    }
}
