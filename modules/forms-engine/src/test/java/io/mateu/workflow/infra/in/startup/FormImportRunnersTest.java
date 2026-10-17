package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase.ImportFormsResult;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.config.GitImportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The startup imports: nothing configured, nothing imported; configured, the forms are loaded once. */
class FormImportRunnersTest {

    final DefaultApplicationArguments args = new DefaultApplicationArguments();

    @Test
    void withoutDirectoriesTheDirectoryImportDoesNotRun() {
        var useCase = mock(ImportFormsFromDirectoryUseCase.class);
        new FormDirectoryImportRunner(new DirectoryImportProperties(), useCase).run(args);
        verify(useCase, never()).handle();
    }

    @Test
    void withDirectoriesTheyAreImportedAtStartupWhateverTheOutcome() {
        var properties = new DirectoryImportProperties();
        properties.setDirectories(List.of("/forms"));
        var useCase = mock(ImportFormsFromDirectoryUseCase.class);
        when(useCase.handle()).thenReturn(new ImportFormsFromDirectoryUseCase.ImportFormsResult(List.of("A [a]"), List.of("bad.yml: x"),
                List.of("Old [old]")));

        new FormDirectoryImportRunner(properties, useCase).run(args);

        verify(useCase).handle();
    }

    @Test
    void withoutRepositoriesTheGitImportDoesNotRun() {
        var useCase = mock(ImportFormsFromGitUseCase.class);
        new FormGitImportRunner(new GitImportProperties(), useCase).run(args);
        verify(useCase, never()).handle();
    }

    @Test
    void withRepositoriesTheyAreImportedAtStartup() {
        var properties = new GitImportProperties();
        var repository = new GitImportProperties.GitRepository();
        repository.setUrl("https://example.com/forms.git");
        properties.setRepositories(List.of(repository));
        var useCase = mock(ImportFormsFromGitUseCase.class);
        when(useCase.handle()).thenReturn(new ImportFormsResult(List.of("A [a]"), List.of("e"), List.of()));

        new FormGitImportRunner(properties, useCase).run(args);

        verify(useCase).handle();
    }
}
