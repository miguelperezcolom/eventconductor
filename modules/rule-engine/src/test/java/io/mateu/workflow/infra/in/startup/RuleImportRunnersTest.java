package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase.ImportRulesResult;
import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The startup imports: nothing configured, nothing imported; configured, the catalogue is loaded once. */
class RuleImportRunnersTest {

    final DefaultApplicationArguments args = new DefaultApplicationArguments();

    @Test
    void withoutDirectoriesTheDirectoryImportDoesNotRun() {
        var useCase = mock(ImportRulesFromDirectoryUseCase.class);
        new RuleDirectoryImportRunner(new RuleDirectoryImportProperties(), useCase).run(args);
        verify(useCase, never()).handle();
    }

    @Test
    void withDirectoriesTheyAreImportedAtStartupWhateverTheOutcome() {
        var properties = new RuleDirectoryImportProperties();
        properties.setDirectories(List.of("/rules"));
        var useCase = mock(ImportRulesFromDirectoryUseCase.class);
        when(useCase.handle()).thenReturn(new ImportRulesResult(List.of("A [a]"), List.of("bad.json: x"),
                List.of("Old [old]")));

        new RuleDirectoryImportRunner(properties, useCase).run(args);

        verify(useCase).handle();
    }

    @Test
    void withoutRepositoriesTheGitImportDoesNotRun() {
        var useCase = mock(ImportRulesFromGitUseCase.class);
        new RuleGitImportRunner(new RuleGitImportProperties(), useCase).run(args);
        verify(useCase, never()).handle();
    }

    @Test
    void withRepositoriesTheyAreImportedAtStartup() {
        var properties = new RuleGitImportProperties();
        var repository = new RuleGitImportProperties.GitRepository();
        repository.setUrl("https://example.com/rules.git");
        properties.setRepositories(List.of(repository));
        var useCase = mock(ImportRulesFromGitUseCase.class);
        when(useCase.handle()).thenReturn(new ImportRulesResult(List.of("A [a]"), List.of("e"), List.of()));

        new RuleGitImportRunner(properties, useCase).run(args);

        verify(useCase).handle();
    }
}
