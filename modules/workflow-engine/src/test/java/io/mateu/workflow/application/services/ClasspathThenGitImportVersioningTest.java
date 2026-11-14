package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository;
import io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionImporter;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntity;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntityRepository;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The same file imported from the classpath at boot and then from a Git clone (or a directory)
 * records one version, not two: the two import paths build the definition slightly differently
 * (the classpath import stamps each step with the definition's id), and that must not read as a
 * content change.
 */
class ClasspathThenGitImportVersioningTest {

    private static final String DEFINITION = """
            id: booking
            name: Booking
            steps:
              - id: start
                type: START
                name: Start
              - id: reserve
                type: ACTION
                name: Reserve
                preconditionStepId: start
              - id: end
                type: END
                name: End
                preconditionStepId: reserve
            """;

    /** The versioning service over an in-memory version history. */
    private final List<WorkflowDefinitionVersionEntity> history = new ArrayList<>();
    private final WorkflowDefinitionVersioningService versioning = versioningOverHistory();

    /** A repository that does what the JPA one does on save: reconcile the version. */
    private final Map<String, WorkflowDefinition> rows = new HashMap<>();
    private final WorkflowDefinitionRepository repository = new WorkflowDefinitionRepository() {
        @Override public Optional<WorkflowDefinition> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public String save(WorkflowDefinition d) {
            rows.put(d.id(), d.withVersion(versioning.reconcile(d)));
            return d.id();
        }
        @Override public List<WorkflowDefinition> findAll() { return List.copyOf(rows.values()); }
        @Override public void deleteAllById(List<String> ids) { ids.forEach(rows::remove); }
    };

    private final TaskReferenceResolver tasks = new TaskReferenceResolver(new ClasspathTaskContractRepository());

    private WorkflowDefinitionVersioningService versioningOverHistory() {
        var versions = mock(WorkflowDefinitionVersionEntityRepository.class);
        when(versions.findTopByDefinitionIdOrderByVersionDesc(anyString())).thenAnswer(call -> history.stream()
                .filter(v -> v.getDefinitionId().equals(call.getArgument(0)))
                .reduce((a, b) -> b));
        when(versions.save(any())).thenAnswer(call -> {
            history.add(call.getArgument(0));
            return call.getArgument(0);
        });
        return new WorkflowDefinitionVersioningService(versions);
    }

    @Test
    void anIdenticalGitImportAfterTheClasspathImportRecordsNoNewVersion(
            @TempDir Path classpathRoot, @TempDir Path clone) throws Exception {
        Files.writeString(Files.createDirectories(classpathRoot.resolve("workflows")).resolve("booking.yaml"), DEFINITION);
        Files.writeString(clone.resolve("booking.yaml"), DEFINITION);

        // Boot: the classpath import.
        var importer = new ClasspathWorkflowDefinitionImporter(repository, new WorkflowDefinitionValidator(), tasks);
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[]{classpathRoot.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            importer.run(new DefaultApplicationArguments());
        } finally {
            thread.setContextClassLoader(previous);
        }
        assertThat(history).extracting(WorkflowDefinitionVersionEntity::getVersion).containsExactly(1);

        // Then the Git import of the same content (a clone is imported as a directory).
        var directoryImport = new ImportWorkflowDefinitionsFromDirectoryUseCase(
                mock(DirectoryImportProperties.class), repository, new InMemoryImportedDefinitionsRegistry(),
                new WorkflowDefinitionValidator(), tasks);
        var errors = new ArrayList<String>();
        directoryImport.scanAndImport(clone, new ArrayList<>(), errors, new LinkedHashSet<>());

        assertThat(errors).isEmpty();
        assertThat(history).extracting(WorkflowDefinitionVersionEntity::getVersion).containsExactly(1);
        assertThat(rows.get("booking").version()).isEqualTo(1);

        // A real change still records v2.
        Files.writeString(clone.resolve("booking.yaml"), DEFINITION.replace("name: Reserve", "name: Reserve the room"));
        directoryImport.scanAndImport(clone, new ArrayList<>(), errors, new LinkedHashSet<>());
        assertThat(errors).isEmpty();
        assertThat(history).extracting(WorkflowDefinitionVersionEntity::getVersion).containsExactly(1, 2);
    }
}
