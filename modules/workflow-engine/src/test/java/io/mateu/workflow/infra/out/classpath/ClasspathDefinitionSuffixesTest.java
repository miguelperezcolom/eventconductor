package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
 * Both classpath loaders — the memory repository and the JPA importer — read {@code .ec},
 * {@code .json}, {@code .yaml}/{@code .yml} and, through the last, the compound
 * {@code .workflow.yaml} / {@code .ec.yaml} suffixes, each file exactly once.
 *
 * <p>The classpath is a throwaway directory put in front of the loaders as the context class
 * loader, so the definitions here never leak into the other tests' Spring contexts.
 */
class ClasspathDefinitionSuffixesTest {

    @TempDir
    Path root;

    // Built before the context loader is swapped, so the task repository scans the real classpath
    // for contracts and not the throwaway one.
    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();
    private final TaskReferenceResolver resolver =
            new TaskReferenceResolver(new ClasspathTaskContractRepository());

    private static String yaml(String id) {
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

    @BeforeEach
    void writeDefinitions() throws Exception {
        var workflows = Files.createDirectories(root.resolve("workflows"));
        Files.writeString(workflows.resolve("plain.yaml"), yaml("plain"));
        Files.writeString(workflows.resolve("order.workflow.yaml"), yaml("order"));
        Files.writeString(workflows.resolve("invoice.workflow.yml"), yaml("invoice"));
        Files.writeString(workflows.resolve("refund.ec.yaml"), yaml("refund"));
        Files.writeString(workflows.resolve("payment.ec.yml"), yaml("payment"));
        Files.writeString(workflows.resolve("yaml-flow.ec"), yaml("ec-yaml"));
        Files.writeString(workflows.resolve("json-flow.ec"),
                "{\"id\":\"ec-json\",\"name\":\"ec-json\",\"version\":1,\"steps\":["
                        + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
                        + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"preconditionStepId\":\"start\"}]}");
        Files.writeString(workflows.resolve("notes.md"), "not a definition");
    }

    private <T> T onThisClasspath(Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        // In front of the real classpath (the validator reads its schema through this loader);
        // nothing else on the test classpath has a workflows/ directory.
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void theMemoryRepositoryLoadsEverySuffixOnce() throws Exception {
        var repository = onThisClasspath(() -> new ClasspathWorkflowDefinitionRepository(resolver));

        assertThat(repository.findAll()).extracting(WorkflowDefinition::id).containsExactlyInAnyOrder(
                "plain", "order", "invoice", "refund", "payment", "ec-yaml", "ec-json");
    }

    @Test
    void theJpaImporterImportsEverySuffixOnce() throws Exception {
        var repository = mock(WorkflowDefinitionRepository.class);
        var importer = new ClasspathWorkflowDefinitionImporter(repository, validator, resolver);

        onThisClasspath(() -> {
            importer.importDefinitions();
            return null;
        });

        var saved = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository, atLeast(0)).save(saved.capture());
        // containsExactlyInAnyOrder also proves nothing was saved twice by overlapping patterns.
        assertThat(saved.getAllValues()).extracting(WorkflowDefinition::id).containsExactlyInAnyOrder(
                "plain", "order", "invoice", "refund", "payment", "ec-yaml", "ec-json");
    }
}
