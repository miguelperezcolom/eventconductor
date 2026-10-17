package io.mateu.workflow.application.usecases.directoryimport;

import io.mateu.workflow.application.services.CronScheduleImporter;
import io.mateu.workflow.application.services.EventTriggerImporter;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository;
import io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
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
 * An {@code events.yaml} beside the definitions of an imported directory (or git clone, which is the
 * same import): its entries become that source's event triggers, it is never taken for a definition,
 * and re-importing replaces what it said.
 */
class ImportEventFilesFromDirectoryTest {

    private final TaskReferenceResolver taskReferenceResolver =
            new TaskReferenceResolver(new ClasspathTaskContractRepository());
    private final ClasspathWorkflowDefinitionRepository definitions =
            new ClasspathWorkflowDefinitionRepository(taskReferenceResolver);
    private final InMemoryEventTriggerRepository triggers = new InMemoryEventTriggerRepository();
    private final ImportWorkflowDefinitionsFromDirectoryUseCase useCase =
            new ImportWorkflowDefinitionsFromDirectoryUseCase(
                    mock(DirectoryImportProperties.class), definitions, new InMemoryImportedDefinitionsRegistry(),
                    new WorkflowDefinitionValidator(), taskReferenceResolver,
                    new CronScheduleImporter(definitions, new InMemoryCronScheduleRepository(), new InMemorySchedulePauseRepository()),
                    new EventTriggerImporter(definitions, triggers, new InMemoryTriggerPauseRepository()));

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
    void eventsYamlBecomesTheDirectorysTriggersAndIsNotADefinition(@TempDir Path dir) throws Exception {
        writeDefinition(dir, "sync-customer", """
                triggers:
                  - destination: bookings
                    type: com.acme.booking.created
                """);
        Files.writeString(dir.resolve("events.yaml"), """
                - destination: customers-cdc
                  type: "*"
                  workflow: sync-customer
                  filter: "data.op == 'u'"
                  variables: {customerId: "${data.after.id}"}
                - destination: bookings
                  type: com.acme.booking.created
                  workflow: does-not-exist
                """);

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(definitions.findAll()).extracting(d -> d.id()).containsExactly("sync-customer");
        assertThat(definitions.findById("sync-customer").orElseThrow().triggers()).containsExactly(
                new EventTrigger(null, "bookings", "com.acme.booking.created", null, null, null));
        assertThat(triggers.findAll()).containsExactly(new EventFileTrigger(dir.toString(), 0, "sync-customer",
                new EventTrigger(null, "customers-cdc", "*", "data.op == 'u'", null, Map.of("customerId", "${data.after.id}"))));
        assertThat(result.errors()).singleElement().asString().contains("unknown workflow 'does-not-exist'");
    }

    @Test
    void reimportingReplacesTheSourcesTriggersAndRemovingTheFileClearsThem(@TempDir Path dir) throws Exception {
        writeDefinition(dir, "sync-customer", "");
        Files.writeString(dir.resolve("events.yaml"), """
                - destination: customers-cdc
                  type: "*"
                  workflow: sync-customer
                """);
        useCase.handle(List.of(dir.toString()));
        assertThat(triggers.findAll()).hasSize(1);

        Files.writeString(dir.resolve("events.yaml"), """
                - destination: bookings
                  type: com.acme.booking.*
                  workflow: sync-customer
                - destination: payments
                  type: com.acme.payment.*
                  workflow: sync-customer
                """);
        useCase.handle(List.of(dir.toString()));
        assertThat(triggers.findAll()).extracting(t -> t.trigger().destination()).containsExactly("bookings", "payments");

        Files.delete(dir.resolve("events.yaml"));
        useCase.handle(List.of(dir.toString()));
        assertThat(triggers.findAll()).isEmpty();
    }
}
