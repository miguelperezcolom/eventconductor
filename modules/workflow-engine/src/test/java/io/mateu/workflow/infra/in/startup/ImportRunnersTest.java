package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.application.usecases.taskcontractimport.ImportTasksFromDirectoryUseCase;
import io.mateu.workflow.application.usecases.taskcontractimport.ImportTasksFromGitUseCase;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.infra.config.TaskContractDirectoryImportProperties;
import io.mateu.workflow.infra.config.TaskContractGitImportProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The four import runners: with nothing configured they do not touch the import at all; with
 * something configured they run it once at startup, and what it reports — imported, pruned or
 * errors — never fails the boot.
 */
class ImportRunnersTest {

    @Test
    void workflowDirectoryImport() {
        var properties = new DirectoryImportProperties();
        var useCase = mock(ImportWorkflowDefinitionsFromDirectoryUseCase.class);
        new WorkflowDefinitionDirectoryImportRunner(properties, useCase).run(null);
        verifyNoInteractions(useCase);

        properties.getDirectories().add("/defs");
        when(useCase.handle()).thenReturn(new ImportWorkflowDefinitionsFromDirectoryUseCase.ImportWorkflowDefinitionsResult(
                List.of("Order [order]"), List.of("File broken.yaml: bad"), List.of("Gone [gone]")));
        assertThatCode(() -> new WorkflowDefinitionDirectoryImportRunner(properties, useCase).run(null))
                .doesNotThrowAnyException();
        verify(useCase).handle();
    }

    @Test
    void workflowGitImport() {
        var properties = new GitImportProperties();
        var useCase = mock(ImportWorkflowDefinitionsFromGitUseCase.class);
        new WorkflowDefinitionGitImportRunner(properties, useCase).run(null);
        verifyNoInteractions(useCase);

        properties.getRepositories().add(new GitImportProperties.GitRepository());
        when(useCase.handle()).thenReturn(new ImportWorkflowDefinitionsFromGitUseCase.ImportWorkflowDefinitionsResult(
                List.of("Order [order]"), List.of("Repository x: unreachable"), List.of()));
        assertThatCode(() -> new WorkflowDefinitionGitImportRunner(properties, useCase).run(null))
                .doesNotThrowAnyException();
        verify(useCase).handle();
    }

    @Test
    void taskContractDirectoryImport() {
        var properties = new TaskContractDirectoryImportProperties();
        var useCase = mock(ImportTasksFromDirectoryUseCase.class);
        new TaskContractDirectoryImportRunner(properties, useCase).run(null);
        verifyNoInteractions(useCase);

        properties.getDirectories().add("/tasks");
        when(useCase.handle()).thenReturn(new ImportTasksFromDirectoryUseCase.ImportTasksResult(
                List.of("greet@1"), List.of("File broken.ectask: bad")));
        assertThatCode(() -> new TaskContractDirectoryImportRunner(properties, useCase).run(null))
                .doesNotThrowAnyException();
        verify(useCase).handle();
    }

    @Test
    void taskContractGitImport() {
        var properties = new TaskContractGitImportProperties();
        var useCase = mock(ImportTasksFromGitUseCase.class);
        new TaskContractGitImportRunner(properties, useCase).run(null);
        verifyNoInteractions(useCase);

        properties.getRepositories().add(new TaskContractGitImportProperties.GitRepository());
        when(useCase.handle()).thenReturn(new ImportTasksFromDirectoryUseCase.ImportTasksResult(
                List.of("greet@1"), List.of("Repository x: unreachable")));
        assertThatCode(() -> new TaskContractGitImportRunner(properties, useCase).run(null))
                .doesNotThrowAnyException();
        verify(useCase).handle();
    }
}
