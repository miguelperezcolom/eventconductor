package io.mateu.workflow.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

    @TempDir
    Path work;

    private Path tasks;
    private Path out;
    private Path resources;
    private MavenProject project;
    private GenerateMojo mojo;

    @BeforeEach
    void setUp() throws Exception {
        tasks = Files.createDirectories(work.resolve("tasks"));
        out = work.resolve("gen-sources");
        resources = work.resolve("gen-resources");
        project = new MavenProject();
        mojo = new GenerateMojo();
        set("tasksDirectory", tasks.toFile());
        set("outputDirectory", out.toFile());
        set("resourcesDirectory", resources.toFile());
        set("definitionsCache", work.resolve("defs-cache").toFile());
        set("basePackage", "com.acme.tasks");
        set("project", project);
    }

    private void set(String field, Object value) throws Exception {
        Field f = GenerateMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }

    private void writeTask(String name, String yaml) throws Exception {
        Files.writeString(tasks.resolve(name), yaml);
    }

    @Test
    void generates_sources_and_imports_and_registers_the_roots() throws Exception {
        writeTask("greet.ectask", """
            id: greet
            version: 1
            group: greetings
            input:
              name: {type: string, required: true}
            output:
              message: {type: string}
            """);

        mojo.execute();

        assertThat(out.resolve("com/acme/tasks/greetings/GreetV1Task.java")).exists();
        assertThat(out.resolve("com/acme/tasks/greetings/GreetV1Input.java")).exists();
        assertThat(out.resolve("com/acme/tasks/greetings/GreetV1Output.java")).exists();
        assertThat(out.resolve("com/acme/tasks/greetings/GreetingsTasksAutoConfiguration.java")).exists();

        var imports = resources.resolve(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(imports).exists();
        assertThat(Files.readString(imports))
                .isEqualTo("com.acme.tasks.greetings.GreetingsTasksAutoConfiguration\n");

        assertThat(project.getCompileSourceRoots()).contains(out.toFile().getAbsolutePath());
        assertThat(project.getResources()).anyMatch(r -> r.getDirectory().equals(resources.toFile().getAbsolutePath()));
    }

    @Test
    void the_group_filter_limits_what_is_generated() throws Exception {
        writeTask("a.ectask", "id: a\nversion: 1\ngroup: alpha\n");
        writeTask("b.ectask", "id: b\nversion: 1\ngroup: beta\n");
        set("group", "alpha");

        mojo.execute();

        assertThat(out.resolve("com/acme/tasks/alpha/AV1Task.java")).exists();
        assertThat(out.resolve("com/acme/tasks/beta/BV1Task.java")).doesNotExist();
    }

    @Test
    void the_tasks_filter_selects_by_id_or_ref() throws Exception {
        writeTask("a.ectask", "id: a\nversion: 1\ngroup: g\n");
        writeTask("b.ectask", "id: b\nversion: 1\ngroup: g\n");
        writeTask("c.ectask", "id: c\nversion: 2\ngroup: g\n");
        set("tasks", "a, c@2");

        mojo.execute();

        assertThat(out.resolve("com/acme/tasks/g/AV1Task.java")).exists();
        assertThat(out.resolve("com/acme/tasks/g/CV2Task.java")).exists();
        assertThat(out.resolve("com/acme/tasks/g/BV1Task.java")).doesNotExist();
    }

    @Test
    void an_application_class_is_generated_for_a_standalone_service() throws Exception {
        writeTask("greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n");
        set("applicationClass", "com.acme.svc.TaskServiceApplication");

        mojo.execute();

        assertThat(out.resolve("com/acme/svc/TaskServiceApplication.java")).exists();
        assertThat(Files.readString(out.resolve("com/acme/svc/TaskServiceApplication.java")))
                .contains("@org.springframework.boot.autoconfigure.SpringBootApplication");
    }

    @Test
    void a_non_contract_file_is_ignored() throws Exception {
        writeTask("notes.yaml", "hello: world\n");

        mojo.execute();

        assertThat(out).doesNotExist();
        // roots are still registered so the build stays consistent
        assertThat(project.getCompileSourceRoots()).contains(out.toFile().getAbsolutePath());
    }

    @Test
    void skip_generates_nothing() throws Exception {
        writeTask("greet.ectask", "id: greet\nversion: 1\ngroup: greetings\n");
        set("skip", true);

        mojo.execute();

        assertThat(out).doesNotExist();
    }

    @Test
    void regeneration_removes_sources_for_a_removed_contract() throws Exception {
        writeTask("a.ectask", "id: a\nversion: 1\ngroup: alpha\n");
        mojo.execute();
        assertThat(out.resolve("com/acme/tasks/alpha/AV1Task.java")).exists();

        Files.delete(tasks.resolve("a.ectask"));
        writeTask("b.ectask", "id: b\nversion: 1\ngroup: beta\n");
        mojo.execute();

        assertThat(out.resolve("com/acme/tasks/alpha/AV1Task.java")).doesNotExist();
        assertThat(out.resolve("com/acme/tasks/beta/BV1Task.java")).exists();
    }

    @Test
    void a_malformed_definitions_artifact_coordinate_is_rejected() throws Exception {
        set("definitions", "not-a-gav");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mojo.execute())
                .isInstanceOf(org.apache.maven.plugin.MojoExecutionException.class)
                .hasMessageContaining("groupId:artifactId:version");
    }

    @Test
    void resolves_definitions_from_a_maven_artifact_zip() throws Exception {
        var zip = work.resolve("defs.zip");
        try (var zout = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            zout.putNextEntry(new java.util.zip.ZipEntry("tasks/greet.ectask"));
            zout.write("id: greet\nversion: 1\ngroup: greetings\n".getBytes());
            zout.closeEntry();
        }
        set("definitions", "com.acme:defs:1.0.0");
        var field = GenerateMojo.class.getDeclaredField("zipResolver");
        field.setAccessible(true);
        field.set(mojo, (GenerateMojo.ZipResolver) gav -> zip);

        mojo.execute();

        assertThat(out.resolve("com/acme/tasks/greetings/GreetV1Task.java")).exists();
    }

    @Test
    void a_git_repository_without_a_ref_is_rejected() throws Exception {
        set("repository", "https://example.com/defs.git");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mojo.execute())
                .isInstanceOf(org.apache.maven.plugin.MojoExecutionException.class)
                .hasMessageContaining("ref");
    }

    @Test
    void unzip_expands_a_bundle_and_refuses_zip_slip() throws Exception {
        var zip = work.resolve("bundle.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("tasks/greet.ectask"));
            out.write("id: greet\nversion: 1\ngroup: g\n".getBytes());
            out.closeEntry();
        }
        var target = work.resolve("unpacked");
        GenerateMojo.unzip(zip, target);
        assertThat(target.resolve("tasks/greet.ectask")).exists();

        var evil = work.resolve("evil.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) {
            out.putNextEntry(new java.util.zip.ZipEntry("../escape.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GenerateMojo.unzip(evil, work.resolve("u2")))
                .isInstanceOf(java.io.IOException.class);
    }

    private Path gitRepoWithGreet() throws Exception {
        var repo = Files.createDirectories(work.resolve("defs-repo"));
        Files.createDirectories(repo.resolve("tasks"));
        Files.writeString(repo.resolve("tasks/greet.ectask"), "id: greet\nversion: 1\ngroup: greetings\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        git(repo, "tag", "v1");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }

    @Test
    void resolves_definitions_from_a_git_tag() throws Exception {
        var repo = gitRepoWithGreet();
        set("repository", repo.toString());
        set("ref", "v1");

        mojo.execute();

        assertThat(out.resolve("com/acme/tasks/greetings/GreetV1Task.java")).exists();
    }

    @Test
    void refuses_a_git_branch_ref_unless_allowed() throws Exception {
        var repo = gitRepoWithGreet();
        set("repository", repo.toString());
        set("ref", "main");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mojo.execute())
                .isInstanceOf(org.apache.maven.plugin.MojoExecutionException.class)
                .hasMessageContaining("branch");

        set("allowBranch", true);
        mojo.execute();
        assertThat(out.resolve("com/acme/tasks/greetings/GreetV1Task.java")).exists();
    }

    @Test
    void find_tasks_directory_prefers_a_tasks_subfolder() throws Exception {
        var root = Files.createDirectories(work.resolve("bundle"));
        assertThat(GenerateMojo.findTasksDirectory(root)).isEqualTo(root);

        var withTasks = Files.createDirectories(work.resolve("b2/tasks"));
        assertThat(GenerateMojo.findTasksDirectory(withTasks.getParent())).isEqualTo(withTasks);

        var nested = Files.createDirectories(work.resolve("b3/definitions/tasks"));
        assertThat(GenerateMojo.findTasksDirectory(nested.getParent().getParent())).isEqualTo(nested);
    }
}
