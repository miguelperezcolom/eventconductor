package io.mateu.workflow.application.usecases.taskcontractimport;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.application.usecases.gitimport.LocalGitRepo;
import io.mateu.workflow.infra.config.TaskContractDirectoryImportProperties;
import io.mateu.workflow.infra.config.TaskContractGitImportProperties;
import io.mateu.workflow.tasks.TaskContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task-contract git import against a real repository cloned over {@code file://}: first import,
 * re-import, a removed file (kept — contracts are append-only), a broken file, a subdirectory, and a
 * URL whose credentials never appear in what is reported.
 */
class ImportTasksFromGitUseCaseTest {

    @TempDir
    Path tmp;

    private final Map<String, TreeMap<Integer, TaskContract>> store = new ConcurrentHashMap<>();

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

    private LocalGitRepo git;
    private TaskContractGitImportProperties properties;
    private ImportTasksFromGitUseCase useCase;

    @BeforeEach
    void setUp() throws Exception {
        git = new LocalGitRepo(tmp);
        properties = new TaskContractGitImportProperties();
        useCase = new ImportTasksFromGitUseCase(properties,
                new ImportTasksFromDirectoryUseCase(new TaskContractDirectoryImportProperties(), repository));
    }

    @AfterEach
    void tearDown() throws Exception {
        git.close();
    }

    private TaskContractGitImportProperties.GitRepository repo(String url, String directory) {
        var repo = new TaskContractGitImportProperties.GitRepository();
        repo.setUrl(url);
        repo.setDirectory(directory);
        properties.getRepositories().add(repo);
        return repo;
    }

    private static Map<String, String> files(String... pathsAndContents) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            map.put(pathsAndContents[i], pathsAndContents[i + 1]);
        }
        return map;
    }

    @Test
    void aFirstImportStoresEveryContractAndIgnoresFilesThatAreNotContracts() throws Exception {
        git.push(files(
                "tasks/greet.ectask", "id: greet\nversion: 1\ngroup: greetings\ntopic: greetings\n",
                "tasks/charge.json", "{\"id\":\"charge\",\"version\":3,\"group\":\"payments\"}",
                "flows/order.yaml", "name: Order\nsteps: []\n"));
        repo(git.url(), null);

        var result = useCase.handle();

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).containsExactlyInAnyOrder("greet@1", "charge@3");
        assertThat(repository.find("greet", 1)).map(TaskContract::topic).contains("greetings");
    }

    @Test
    void aReImportAddsTheNewVersionAndARemovedFileKeepsItsContract() throws Exception {
        git.push(files("greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n"));
        repo(git.url(), null);
        useCase.handle();

        git.push(files("greet.ectask", null, "greet-v2.ectask", "id: greet\nversion: 2\ngroup: greetings\n"));
        var result = useCase.handle();

        assertThat(result.imported()).containsExactly("greet@2");
        // Append-only: a process pinned to greet@1 must keep finding it.
        assertThat(repository.find("greet", 1)).isPresent();
        assertThat(repository.findLatest("greet")).map(TaskContract::version).contains(2);
    }

    @Test
    void aBrokenFileIsReportedByNameAndTheRestStillImport() throws Exception {
        git.push(files("greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n",
                "broken.ectask", "id: [ : : nope"));
        repo(git.url(), null);

        var result = useCase.handle();

        assertThat(result.imported()).containsExactly("greet@1");
        assertThat(result.errors()).singleElement().asString().startsWith("File broken.ectask");
    }

    @Test
    void onlyTheConfiguredSubdirectoryIsScanned() throws Exception {
        git.push(files("tasks/greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n",
                "elsewhere/charge.ectask", "id: charge\nversion: 1\ngroup: payments\n"));
        repo(git.url(), "tasks");

        assertThat(useCase.handle().imported()).containsExactly("greet@1");
    }

    @Test
    void aMissingOrEscapingSubdirectoryIsAnErrorAndOtherRepositoriesStillImport() throws Exception {
        git.push(files("greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n"));
        repo(git.url(), "nowhere");
        repo(git.url(), "../..");
        repo(git.url(), "  ");

        var result = useCase.handle();

        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0)).contains("directory 'nowhere' not found in repository");
        assertThat(result.errors().get(1)).contains("escapes the repository root");
        assertThat(result.imported()).containsExactly("greet@1");
    }

    @Test
    void configuredCredentialsDoNotStopALocalClone() throws Exception {
        git.push(files("greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n"));
        var repo = repo(git.url(), null);
        repo.setUsername("ci-bot");
        repo.setPassword("fake-secret-token");

        assertThat(useCase.handle().imported()).containsExactly("greet@1");
    }

    @Test
    void aRepositoryThatCannotBeClonedIsReportedWithoutTheCredentialsInItsUrl() {
        // Nothing listens on port 1 of the loopback: the clone fails without leaving the machine.
        repo("https://ci-bot:fake-secret-token@127.0.0.1:1/org/tasks.git", null);
        repo("https://fake-secret-token@127.0.0.1:1/org/tasks.git", null);

        var result = useCase.handle();

        assertThat(result.errors()).hasSize(2).allSatisfy(error -> assertThat(error)
                .startsWith("Repository https://******@127.0.0.1:1/org/tasks.git")
                .doesNotContain("fake-secret-token")
                .doesNotContain("ci-bot"));
        assertThat(result.imported()).isEmpty();
    }
}
