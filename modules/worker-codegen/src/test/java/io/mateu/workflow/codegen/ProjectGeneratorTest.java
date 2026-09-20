package io.mateu.workflow.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.codegen.GeneratedProject.GeneratedProjectFile;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProjectGeneratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode contract(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final String GREET = """
        {"id": "greet", "version": 1, "group": "greetings", "topic": "greetings",
         "description": "Greet someone",
         "input": {"name": {"type": "string", "required": true}},
         "output": {"message": {"type": "string"}},
         "errors": [{"code": "UNKNOWN_PERSON", "description": "no such person"}]}""";

    private Map<String, String> files(GeneratedProject project) {
        return project.files().stream()
                .collect(Collectors.toMap(GeneratedProjectFile::path, GeneratedProjectFile::content));
    }

    @Test
    void a_task_module_is_a_pom_and_a_readme_only() {
        var spec = new ProjectSpec(ProjectSpec.Variant.TASK_MODULE, "com.acme", "greetings-tasks",
                "0.1.0", "1.0-SNAPSHOT", "com.acme:defs:1.2.0", null, null, "greetings", null,
                "com.acme.tasks", List.of(contract(GREET)), false, false);

        var files = files(new ProjectGenerator().generate(spec));

        assertThat(files).containsOnlyKeys("pom.xml", "README.md");
        assertThat(files.get("pom.xml"))
                .contains("<artifactId>task-module-parent</artifactId>")
                .contains("<groupId>com.acme</groupId>")
                .contains("<artifactId>greetings-tasks</artifactId>")
                .contains("<ec.definitions>com.acme:defs:1.2.0</ec.definitions>")
                .contains("<ec.group>greetings</ec.group>")
                .contains("<ec.basePackage>com.acme.tasks</ec.basePackage>")
                .doesNotContain("ec.repository");
        assertThat(files.get("README.md"))
                .contains("### `GreetV1Task` — task `greet@1`")
                .contains("**Input** (`GreetV1Input`): `name`: String")
                .contains("**Output** (`GreetV1Output`): `message`: String")
                .contains("`UnknownPerson` (UNKNOWN_PERSON)");
    }

    @Test
    void a_task_service_carries_the_wrapper_yaml_and_gitignore() {
        var spec = new ProjectSpec(ProjectSpec.Variant.TASK_SERVICE, "com.acme", "greetings-service",
                "0.1.0", "1.0-SNAPSHOT", null, "https://git/acme/defs.git", "v1.2.0", null,
                "greet@1", "com.acme.svc", List.of(contract(GREET)), true, true);

        var files = files(new ProjectGenerator().generate(spec));

        assertThat(files).containsKeys("pom.xml", "README.md", "src/main/resources/application.yaml",
                ".gitignore", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties");
        assertThat(files.get("pom.xml"))
                .contains("<artifactId>task-service-parent</artifactId>")
                .contains("<ec.repository>https://git/acme/defs.git</ec.repository>")
                .contains("<ec.ref>v1.2.0</ec.ref>")
                .contains("<ec.tasks>greet@1</ec.tasks>")
                .doesNotContain("ec.definitions");
        assertThat(files.get("src/main/resources/application.yaml")).contains("name: greetings-service");
        assertThat(files.get(".mvn/wrapper/maven-wrapper.properties")).contains("distributionUrl=");
    }

    @Test
    void the_readme_says_void_and_none_when_a_task_has_no_shape() {
        var spec = new ProjectSpec(ProjectSpec.Variant.TASK_MODULE, "com.acme", "ops-tasks",
                "0.1.0", "1.0-SNAPSHOT", "com.acme:defs:1", null, null, "ops", null,
                "com.acme.tasks", List.of(contract("{\"id\":\"ping\",\"version\":1,\"group\":\"ops\"}")),
                false, false);

        var readme = files(new ProjectGenerator().generate(spec)).get("README.md");

        assertThat(readme)
                .contains("**Input** (`void`): none")
                .contains("**Output** (`void`): none")
                .contains("**Errors**: none");
    }
}
