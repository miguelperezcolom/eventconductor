package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Definitions are read from the {@code workflows/} directory of every classpath root — as an app
 * with both {@code src/main/resources/workflows} and {@code src/test/resources/workflows} has them in
 * a test run — and not only from the first root, which used to hide all of main's definitions. When
 * the same id is in two roots, the first on the classpath (test output, under Maven) wins.
 */
class ClasspathDefinitionRootsTest {

    /** Stands for target/test-classes: first on the classpath. */
    @TempDir
    Path testClasses;

    /** Stands for target/classes. */
    @TempDir
    Path classes;

    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();
    private final TaskReferenceResolver resolver =
            new TaskReferenceResolver(new ClasspathTaskContractRepository());

    private static String yaml(String id, String name) {
        return """
                id: %s
                name: %s
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: end
                    type: END
                    name: End
                    preconditionStepId: start
                """.formatted(id, name);
    }

    private static String json(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"steps\":["
                + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
                + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"preconditionStepId\":\"start\"}]}";
    }

    @BeforeEach
    void writeDefinitions() throws Exception {
        var main = Files.createDirectories(classes.resolve("workflows"));
        Files.writeString(main.resolve("booking.yaml"), yaml("booking", "Booking (main)"));
        Files.writeString(main.resolve("shared.yaml"), yaml("shared", "Shared (main)"));
        // Another extension than the test copy: the patterns are scanned one by one, so this proves
        // the files are regrouped by root before the first-wins rule applies.
        Files.writeString(main.resolve("other.json"), json("other", "Other (main)"));
        Files.writeString(main.resolve("same.yaml"), yaml("same", "Same"));

        var test = Files.createDirectories(testClasses.resolve("workflows"));
        Files.writeString(test.resolve("test-only.yaml"), yaml("test-only", "Test only"));
        Files.writeString(test.resolve("shared.yaml"), yaml("shared", "Shared (test)"));
        Files.writeString(test.resolve("other.yaml"), yaml("other", "Other (test)"));
        Files.writeString(test.resolve("same.yaml"), yaml("same", "Same"));
    }

    private <T> T onThisClasspath(Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(
                new URL[]{testClasses.toUri().toURL(), classes.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void theMemoryRepositoryReadsEveryRootAndTheFirstWins() throws Exception {
        var repository = onThisClasspath(() -> new ClasspathWorkflowDefinitionRepository(resolver));

        assertThat(repository.findAll()).extracting(WorkflowDefinition::id)
                .containsExactlyInAnyOrder("booking", "shared", "other", "same", "test-only");
        assertThat(repository.findById("shared")).get().extracting(WorkflowDefinition::name).isEqualTo("Shared (test)");
        assertThat(repository.findById("other")).get().extracting(WorkflowDefinition::name).isEqualTo("Other (test)");
        assertThat(repository.findById("booking")).get().extracting(WorkflowDefinition::name).isEqualTo("Booking (main)");
    }

    @Test
    void theJpaImporterReadsEveryRootAndTheFirstWins() throws Exception {
        var repository = mock(WorkflowDefinitionRepository.class);
        var importer = new ClasspathWorkflowDefinitionImporter(repository, validator, resolver);

        onThisClasspath(() -> {
            importer.run(new DefaultApplicationArguments());
            return null;
        });

        var saved = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository, atLeast(0)).save(saved.capture());
        // Exactly once each: a shadowed copy is not saved after the winner.
        assertThat(saved.getAllValues()).extracting(WorkflowDefinition::id)
                .containsExactlyInAnyOrder("booking", "shared", "other", "same", "test-only");
        assertThat(saved.getAllValues()).filteredOn(d -> d.id().equals("shared"))
                .extracting(WorkflowDefinition::name).containsExactly("Shared (test)");
        assertThat(saved.getAllValues()).filteredOn(d -> d.id().equals("other"))
                .extracting(WorkflowDefinition::name).containsExactly("Other (test)");
    }

    @Test
    void theScanListsRootsInClassLoaderOrder() throws Exception {
        var files = onThisClasspath(ClasspathDefinitionFiles::scan);

        assertThat(files).extracting(ClasspathDefinitionFiles.DefinitionFile::filename).containsExactly(
                // test-classes first, in name order…
                "other.yaml", "same.yaml", "shared.yaml", "test-only.yaml",
                // …then classes
                "booking.yaml", "other.json", "same.yaml", "shared.yaml");
    }
}
