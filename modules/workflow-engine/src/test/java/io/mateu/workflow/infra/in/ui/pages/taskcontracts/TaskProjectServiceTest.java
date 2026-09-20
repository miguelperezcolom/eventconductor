package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.codegen.ProjectSpec;
import io.mateu.workflow.tasks.TaskAttribute;
import io.mateu.workflow.tasks.TaskContract;
import io.mateu.workflow.tasks.TaskError;
import io.mateu.workflow.tasks.TaskType;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class TaskProjectServiceTest {

    private final TaskContract greet = new TaskContract("greet", 1, "greetings", "sample-greetings",
            "Greet someone",
            Map.of("name", new TaskAttribute(TaskType.STRING, true, null, null)),
            Map.of("message", new TaskAttribute(TaskType.STRING, false, null, null)),
            List.of(new TaskError("EMPTY_NAME", "blank name")));

    /** A repository serving just the greet contract. */
    private final TaskContractRepository repository = new TaskContractRepository() {
        public Optional<TaskContract> find(String id, int version) {
            return Optional.empty();
        }

        public Optional<TaskContract> findLatest(String id) {
            return Optional.empty();
        }

        public List<TaskContract> findAll() {
            return List.of(greet);
        }

        public String save(TaskContract contract) {
            return contract.ref();
        }
    };

    private final TaskProjectService service = new TaskProjectService(repository);

    private Map<String, String> unzip(byte[] archive) throws Exception {
        var out = new LinkedHashMap<String, String>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), new String(in.readAllBytes()));
            }
        }
        return out;
    }

    @Test
    void a_module_zip_bundles_the_pom_readme_and_the_contract() throws Exception {
        var files = unzip(service.zip("greetings", ProjectSpec.Variant.TASK_MODULE));

        assertThat(files).containsKeys("pom.xml", "README.md",
                "src/main/resources/tasks/greet@1.ectask");
        assertThat(files.get("pom.xml"))
                .contains("<artifactId>task-module-parent</artifactId>")
                .contains("<artifactId>greetings-tasks</artifactId>")
                .contains("<ec.group>greetings</ec.group>");
        assertThat(files.get("src/main/resources/tasks/greet@1.ectask"))
                .contains("\"id\" : \"greet\"")
                .contains("\"EMPTY_NAME\"");
        // no wrapper for a module
        assertThat(files).doesNotContainKey("mvnw");
    }

    @Test
    void a_service_zip_adds_the_service_parent_and_the_wrapper() throws Exception {
        var files = unzip(service.zip("greetings", ProjectSpec.Variant.TASK_SERVICE));

        assertThat(files).containsKeys("pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties",
                "src/main/resources/application.yaml");
        assertThat(files.get("pom.xml")).contains("<artifactId>task-service-parent</artifactId>");
    }

    @Test
    void the_dependency_snippet_names_the_module() {
        assertThat(service.dependencySnippet("greetings"))
                .contains("<artifactId>greetings-tasks</artifactId>");
    }

    @Test
    void an_unknown_group_is_rejected() {
        assertThatThrownBy(() -> service.zip("nope", ProjectSpec.Variant.TASK_MODULE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }
}
