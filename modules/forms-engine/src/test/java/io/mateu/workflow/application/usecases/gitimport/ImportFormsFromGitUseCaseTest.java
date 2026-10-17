package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Importing forms from Git, against a real repository: a local one, committed to by the test and
 * cloned over a {@code file:} URI, so the clone, the scoping and the error reporting all run.
 */
class ImportFormsFromGitUseCaseTest {

    final FormRepository forms = mock(FormRepository.class);
    final FormsMetrics metrics = mock(FormsMetrics.class);
    final ImportFormsFromDirectoryUseCase directoryImport = new ImportFormsFromDirectoryUseCase(
            new DirectoryImportProperties(), forms, metrics, new InMemoryImportedDefinitionsRegistry());

    private ImportFormsFromGitUseCase useCase(GitImportProperties properties) {
        return new ImportFormsFromGitUseCase(properties, metrics, directoryImport);
    }

    private List<String> savedIds() {
        var captor = ArgumentCaptor.forClass(Form.class);
        verify(forms, atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(Form::id).toList();
    }

    @Test
    void everyFormInTheConfiguredRepositoriesIsImported(@TempDir Path repo) throws Exception {
        write(repo, "approve.yml", form("approve"));
        write(repo, "nested/review.json", """
                {"id": "review", "name": "review", "fields": [{"id": "ok", "label": "OK", "dataType": "bool"}]}
                """);
        commit(repo);
        var properties = new GitImportProperties();
        var configured = repository(repo);
        // Credentials are handed to the clone; a file: remote ignores them, a hosted one needs them.
        configured.setUsername("ci");
        configured.setPassword("token");
        properties.setRepositories(List.of(configured));

        var result = useCase(properties).handle();

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).hasSize(2);
        assertThat(savedIds()).containsExactlyInAnyOrder("approve", "review");
        verify(metrics).formsImported(2);
    }

    @Test
    void onlyTheConfiguredDirectoryIsImported(@TempDir Path repo) throws Exception {
        write(repo, "forms/approve.yml", form("approve"));
        write(repo, "other/review.yml", form("review"));
        commit(repo);
        var configured = repository(repo);
        configured.setDirectory("forms");

        var result = useCase(new GitImportProperties()).handle(List.of(configured));

        assertThat(result.errors()).isEmpty();
        assertThat(savedIds()).containsExactly("approve");
    }

    @Test
    void aMissingDirectoryInTheRepositoryIsAnErrorNamingTheRepository(@TempDir Path repo) throws Exception {
        write(repo, "approve.yml", form("approve"));
        commit(repo);
        var configured = repository(repo);
        configured.setDirectory("nope");

        var result = useCase(new GitImportProperties()).handle(List.of(configured));

        assertThat(result.imported()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error).startsWith("Repository ").contains("not found in repository"));
        verify(forms, never()).save(any());
    }

    @Test
    void aRepositoryThatCannotBeClonedIsAnErrorAndNotACrash(@TempDir Path nowhere) {
        var missing = new GitImportProperties.GitRepository();
        missing.setUrl(nowhere.resolve("does-not-exist").toUri().toString());

        var result = useCase(new GitImportProperties()).handle(List.of(missing));

        assertThat(result.imported()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error).startsWith("Repository "));
        verify(metrics).formsImported(0);
    }

    // ── the repository under test ─────────────────────────────────────────────────────────────

    private static GitImportProperties.GitRepository repository(Path repo) {
        var configured = new GitImportProperties.GitRepository();
        configured.setUrl(repo.toUri().toString());
        configured.setBranch("main");
        return configured;
    }

    private static String form(String id) {
        return """
                id: %s
                name: %s
                fields:
                  - id: comment
                    label: Comment
                    dataType: string
                """.formatted(id, id);
    }

    private static void write(Path repo, String name, String content) throws IOException {
        var file = repo.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void commit(Path repo) throws Exception {
        try (var git = Files.exists(repo.resolve(".git"))
                ? Git.open(repo.toFile())
                : Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("forms").setSign(false).setAuthor("test", "test@example.com").call();
        }
    }
}
