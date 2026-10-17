package io.mateu.workflow.application.usecases.directoryimport;

import io.mateu.workflow.application.services.CronScheduleImporter;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.imports.ImportFiles;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository;
import io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A directory (or a clone) is untrusted: a symbolic link in it is not followed — so a committed link
 * cannot make the import read a file outside the repository — and no file is read past the size cap.
 */
class ImportSymlinksAndLargeFilesTest {

    private final TaskReferenceResolver taskReferenceResolver =
            new TaskReferenceResolver(new ClasspathTaskContractRepository());
    private final ClasspathWorkflowDefinitionRepository definitions =
            new ClasspathWorkflowDefinitionRepository(taskReferenceResolver);
    private final InMemoryCronScheduleRepository schedules = new InMemoryCronScheduleRepository();
    private final ImportWorkflowDefinitionsFromDirectoryUseCase useCase =
            new ImportWorkflowDefinitionsFromDirectoryUseCase(
                    mock(DirectoryImportProperties.class), definitions, new InMemoryImportedDefinitionsRegistry(),
                    new WorkflowDefinitionValidator(), taskReferenceResolver,
                    new CronScheduleImporter(definitions, schedules, new InMemorySchedulePauseRepository()));

    private static String definition(String id) {
        return """
                id: %s
                name: %s
                version: 1
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: end
                    type: END
                    name: End
                    preconditionStepId: start
                """.formatted(id, id);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aSymbolicLinkToAFileOutsideTheImportIsNotRead(@TempDir Path dir, @TempDir Path outside) throws Exception {
        Files.writeString(dir.resolve("mine.yaml"), definition("mine"));
        var foreign = Files.writeString(outside.resolve("foreign.yaml"), definition("foreign"));
        Files.createSymbolicLink(dir.resolve("linked.yaml"), foreign);
        var foreignCron = Files.writeString(outside.resolve("cron.yaml"), "- cron: \"@hourly\"\n  workflow: mine\n");
        Files.createSymbolicLink(dir.resolve("cron.yaml"), foreignCron);

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(definitions.findAll()).extracting(d -> d.id()).containsExactly("mine");
        assertThat(schedules.findAll()).as("the linked cron.yaml is not read").isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void aFileOverTheSizeCapIsAnImportErrorAndTheRestImports(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mine.yaml"), definition("mine"));
        var padding = "#".repeat((int) ImportFiles.MAX_FILE_BYTES);
        Files.writeString(dir.resolve("huge.yaml"), definition("huge") + padding + "\n", StandardCharsets.UTF_8);

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(definitions.findAll()).extracting(d -> d.id()).containsExactly("mine");
        assertThat(result.errors()).singleElement().asString().contains("huge.yaml").contains("larger than 5 MB");
    }
}
