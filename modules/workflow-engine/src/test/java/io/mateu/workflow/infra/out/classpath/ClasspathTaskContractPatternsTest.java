package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.tasks.TaskContract;
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
 * Both classpath task-contract loaders — the memory repository and the JPA importer — read
 * {@code .ectask}, {@code .json}, {@code .yaml} and {@code .yml}, each file exactly once, and nothing
 * else. The classpath is only a throwaway directory (platform loader as parent, so the real
 * test classpath's tasks/ never shows up), set as the context class loader.
 */
class ClasspathTaskContractPatternsTest {

    @TempDir
    Path root;

    private static String yaml(String id) {
        return """
                id: %s
                version: 1
                topic: %s
                """.formatted(id, id);
    }

    @BeforeEach
    void writeContracts() throws Exception {
        var tasks = Files.createDirectories(root.resolve("tasks"));
        Files.writeString(tasks.resolve("a.ectask"), yaml("from-ectask"));
        Files.writeString(tasks.resolve("b.yaml"), yaml("from-yaml"));
        Files.writeString(tasks.resolve("c.yml"), yaml("from-yml"));
        Files.writeString(tasks.resolve("d.json"), "{\"id\":\"from-json\",\"version\":1,\"topic\":\"from-json\"}");
        Files.writeString(tasks.resolve("notes.md"), "not a contract");
    }

    private <T> T onThisClasspath(Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void theMemoryRepositoryLoadsEveryExtension() throws Exception {
        var repository = onThisClasspath(ClasspathTaskContractRepository::new);

        assertThat(repository.findAll()).extracting(TaskContract::id)
                .containsExactlyInAnyOrder("from-ectask", "from-yaml", "from-yml", "from-json");
    }

    @Test
    void theJpaImporterImportsEveryFileOnce() throws Exception {
        var repository = mock(TaskContractRepository.class);
        var importer = new ClasspathTaskContractImporter(repository);

        onThisClasspath(() -> {
            importer.importDefinitions();
            return null;
        });

        var saved = ArgumentCaptor.forClass(TaskContract.class);
        verify(repository, atLeast(0)).save(saved.capture());
        // Exactly these four: no file saved twice by overlapping patterns, notes.md never tried.
        assertThat(saved.getAllValues()).extracting(TaskContract::id)
                .containsExactlyInAnyOrder("from-ectask", "from-yaml", "from-yml", "from-json");
    }
}
