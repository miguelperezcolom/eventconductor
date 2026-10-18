package io.mateu.workflow.application.usecases.directoryimport;

import io.mateu.workflow.application.services.CronScheduleImporter;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository;
import io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A {@code cron.yaml} beside the definitions of an imported directory (or git clone, which is the
 * same import): its entries become that source's schedules, it is never taken for a definition, and
 * re-importing replaces what it said.
 */
class ImportCronFilesFromDirectoryTest {

    private final TaskReferenceResolver taskReferenceResolver =
            new TaskReferenceResolver(new ClasspathTaskContractRepository());
    private final ClasspathWorkflowDefinitionRepository definitions =
            new ClasspathWorkflowDefinitionRepository(taskReferenceResolver);
    private final InMemoryCronScheduleRepository schedules = new InMemoryCronScheduleRepository();
    private final ImportWorkflowDefinitionsFromDirectoryUseCase useCase =
            new ImportWorkflowDefinitionsFromDirectoryUseCase(
                    mock(DirectoryImportProperties.class), definitions, new InMemoryImportedDefinitionsRegistry(),
                    new WorkflowDefinitionValidator(), taskReferenceResolver,
                    new CronScheduleImporter(definitions, schedules, new io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository()));

    private static void writeDefinition(Path dir, String id, String extra) throws Exception {
        Files.writeString(dir.resolve(id + ".yaml"), """
                id: %s
                name: %s
                version: 1
                %s
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: end
                    type: END
                    name: End
                    preconditionStepId: start
                """.formatted(id, id, extra));
    }

    @Test
    void cronYamlBecomesTheDirectorysSchedulesAndIsNotADefinition(@TempDir Path dir) throws Exception {
        writeDefinition(dir, "report", """
                schedules:
                  - cron: "0 0 9 * * *"
                    zone: Europe/Madrid
                    variables:
                      region: EMEA
                """);
        Files.writeString(dir.resolve("cron.yaml"), """
                - cron: "0 0 18 * * MON-FRI"
                  workflow: report
                  variables: {region: AMER, copies: 2}
                - cron: "0 0 18 * * *"
                  workflow: does-not-exist
                """);

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(definitions.findAll()).extracting(d -> d.id()).containsExactly("report");
        assertThat(definitions.findById("report").orElseThrow().schedules()).containsExactly(
                new CronSchedule("0 0 9 * * *", "Europe/Madrid", Map.of("region", "EMEA")));
        assertThat(result.imported()).hasSize(1);
        assertThat(result.errors()).singleElement().asString().contains("unknown workflow 'does-not-exist'");
        var source = dir.toAbsolutePath().normalize().toString();
        assertThat(schedules.findAll()).containsExactly(new CronFileSchedule(source, 0, "report",
                new CronSchedule("0 0 18 * * MON-FRI", null, Map.of("region", "AMER", "copies", "2"))));
    }

    @Test
    void reimportingReplacesTheEntriesAndDeletingTheFileStopsThem(@TempDir Path dir) throws Exception {
        writeDefinition(dir, "sweep", "");
        Files.writeString(dir.resolve("cron.yml"), """
                schedules:
                  - cron: "@hourly"
                    workflow: sweep
                  - cron: "@daily"
                    workflow: sweep
                """);
        useCase.handle(List.of(dir.toString()));
        assertThat(schedules.findAll()).hasSize(2);

        Files.writeString(dir.resolve("cron.yml"), """
                schedules:
                  - cron: "@daily"
                    workflow: sweep
                """);
        useCase.handle(List.of(dir.toString()));
        assertThat(schedules.findAll()).extracting(s -> s.schedule().cron()).containsExactly("@daily");

        Files.delete(dir.resolve("cron.yml"));
        useCase.handle(List.of(dir.toString()));
        assertThat(schedules.findAll()).isEmpty();
    }

    @Test
    void aCronFileThatCannotBeReadLeavesTheSourcesSchedulesAsTheyWere(@TempDir Path dir) throws Exception {
        writeDefinition(dir, "sweep", "");
        Files.writeString(dir.resolve("cron.yml"), """
                schedules:
                  - cron: "@hourly"
                    workflow: sweep
                """);
        useCase.handle(List.of(dir.toString()));
        assertThat(schedules.findAll()).hasSize(1);

        // Over the import's size cap: unreadable, which is not the same as empty.
        Files.writeString(dir.resolve("cron.yml"), "schedules: []\n" + "#".repeat(6 * 1024 * 1024));
        var result = useCase.handle(List.of(dir.toString()));

        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("cron.yml"));
        assertThat(schedules.findAll()).extracting(s -> s.schedule().cron()).containsExactly("@hourly");
    }
}
