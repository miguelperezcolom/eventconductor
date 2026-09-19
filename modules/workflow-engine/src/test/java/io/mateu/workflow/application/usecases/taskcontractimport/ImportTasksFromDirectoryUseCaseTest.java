package io.mateu.workflow.application.usecases.taskcontractimport;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.infra.config.TaskContractDirectoryImportProperties;
import io.mateu.workflow.tasks.TaskContract;
import io.mateu.workflow.tasks.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The directory import of {@code .ectask} contracts: a well-formed file lands in the store, several
 * versions of one id coexist, and a file that is not a contract (missing id/version/group) is
 * ignored rather than failing the scan.
 */
class ImportTasksFromDirectoryUseCaseTest {

    private final java.util.Map<String, TreeMap<Integer, TaskContract>> store = new ConcurrentHashMap<>();

    private final TaskContractRepository repository = new TaskContractRepository() {
        public Optional<TaskContract> find(String id, int version) {
            var v = store.get(id);
            return v == null ? Optional.empty() : Optional.ofNullable(v.get(version));
        }
        public Optional<TaskContract> findLatest(String id) {
            var v = store.get(id);
            return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.lastEntry().getValue());
        }
        public List<TaskContract> findAll() {
            return store.values().stream().flatMap(m -> m.values().stream()).toList();
        }
        public String save(TaskContract c) {
            store.computeIfAbsent(c.id(), k -> new TreeMap<>()).put(c.version(), c);
            return c.ref();
        }
    };

    private final ImportTasksFromDirectoryUseCase useCase =
            new ImportTasksFromDirectoryUseCase(new TaskContractDirectoryImportProperties(), repository);

    private void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void imports_contracts_and_keeps_versions(@TempDir Path dir) {
        try {
            write(dir, "greet.ectask", "id: greet\nversion: 1\ngroup: greetings\ntopic: greetings\n");
            write(dir, "greet-v2.ectask", "id: greet\nversion: 2\ngroup: greetings\n");
            write(dir, "not-a-contract.yaml", "name: something\nfoo: bar\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).containsExactlyInAnyOrder("greet@1", "greet@2");
        assertThat(repository.findLatest("greet")).map(TaskContract::version).contains(2);
        assertThat(repository.find("greet", 1)).map(c -> c.topic()).contains("greetings");
    }

    @Test
    void a_missing_directory_is_reported_not_thrown() {
        var result = useCase.handle(List.of("/no/such/dir/anywhere"));
        assertThat(result.imported()).isEmpty();
        assertThat(result.errors()).singleElement().asString().contains("not a directory");
    }

    @Test
    void type_tokens_bind(@TempDir Path dir) throws IOException {
        write(dir, "t.ectask", "id: t\nversion: 1\ngroup: g\ninput:\n  when:\n    type: datetime\n");
        useCase.handle(List.of(dir.toString()));
        assertThat(repository.find("t", 1).orElseThrow().input().get("when").type()).isEqualTo(TaskType.DATETIME);
    }
}
