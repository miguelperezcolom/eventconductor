package io.mateu.workflow.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ProjectZipperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, String> unzip(byte[] archive) throws Exception {
        var out = new LinkedHashMap<String, String>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                out.put(entry.getName(), new String(in.readAllBytes()));
            }
        }
        return out;
    }

    private ProjectSpec moduleSpec() throws Exception {
        var contract = JSON.readTree("{\"id\":\"greet\",\"version\":1,\"group\":\"greetings\"}");
        return new ProjectSpec(ProjectSpec.Variant.TASK_MODULE, "com.acme", "greetings-tasks",
                "0.1.0", "1.0-SNAPSHOT", "com.acme:defs:1", null, null, "greetings", null,
                "com.acme", List.of(contract), false, false);
    }

    @Test
    void zips_the_project_files_and_any_extra_files() throws Exception {
        var project = new ProjectGenerator().generate(moduleSpec());
        var archive = ProjectZipper.zip(project, Map.of(
                "src/main/resources/tasks/greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n"));

        var files = unzip(archive);
        assertThat(files).containsKeys("pom.xml", "README.md",
                "src/main/resources/tasks/greet.ectask");
        assertThat(files.get("pom.xml")).contains("greetings-tasks");
    }

    @Test
    void is_deterministic() throws Exception {
        var project = new ProjectGenerator().generate(moduleSpec());
        var a = ProjectZipper.zip(project, Map.of());
        var b = ProjectZipper.zip(project, Map.of());
        assertThat(b).isEqualTo(a);
    }
}
