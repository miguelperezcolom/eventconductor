package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ImportEcFilesTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final ImportWorkflowDefinitionsFromDirectoryUseCase useCase =
            new ImportWorkflowDefinitionsFromDirectoryUseCase(
                    mock(DirectoryImportProperties.class), repository, new InMemoryImportedDefinitionsRegistry());

    private void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importsEcFilesWhoseContentIsJsonOrYaml(@TempDir Path repo) throws Exception {
        write(repo, "json-flow.ec",
                "{\"id\":\"ec-json\",\"name\":\"EC JSON\","
                        + "\"steps\":[{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"}]}");
        write(repo, "yaml-flow.ec",
                "id: ec-yaml\nname: EC YAML\nsteps:\n  - id: start\n    type: START\n    name: Start\n");

        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var importedIds = new LinkedHashSet<String>();
        useCase.scanAndImport(repo, imported, errors, importedIds);

        assertThat(errors).isEmpty();
        assertThat(importedIds).containsExactlyInAnyOrder("ec-json", "ec-yaml");
        verify(repository).save(argThat((WorkflowDefinition d) -> "EC JSON".equals(d.name())));
        verify(repository).save(argThat((WorkflowDefinition d) -> "EC YAML".equals(d.name())));
    }
}
