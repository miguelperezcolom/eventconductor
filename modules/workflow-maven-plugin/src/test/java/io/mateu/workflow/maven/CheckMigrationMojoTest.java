package io.mateu.workflow.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CheckMigrationMojoTest {

    private final Path fixtures = new File("src/test/resources/migration-check").getAbsoluteFile().toPath();

    private CheckMigrationMojo mojo(String from, String to, String mapping, Path workflows, Path basedir) throws Exception {
        var mojo = new CheckMigrationMojo();
        set(mojo, "from", from);
        set(mojo, "to", to);
        set(mojo, "mapping", mapping == null ? null : new File(mapping));
        set(mojo, "workflowsDirectory", workflows.toFile());
        set(mojo, "basedir", basedir.toFile());
        set(mojo, "failOnError", true);
        set(mojo, "skip", false);
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = CheckMigrationMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void aRenamedStepWithAMappingPasses() throws Exception {
        assertThatCode(() -> mojo("v1", "v2", "mapping.yaml", fixtures.resolve("v2"), fixtures).execute())
                .doesNotThrowAnyException();
        // the same with the target as the working copy, and only one workflow
        var mojo = mojo("v1", "current", fixtures.resolve("mapping.yaml").toString(), fixtures.resolve("v2"), fixtures);
        set(mojo, "workflow", "order");
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void aStepAProcessWaitsInWithoutCounterpartFailsTheBuild() throws Exception {
        assertThatThrownBy(() -> mojo("v1", "v2", null, fixtures.resolve("v2"), fixtures).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("order: ERROR [charge]")
                .hasMessageContaining("no compatible counterpart");
        assertThatThrownBy(() -> mojo("v1", "v2-broken", null, fixtures.resolve("v2"), fixtures).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("the target's 'wait-payment' is a TIMER");
        var lenient = mojo("v1", "v2-broken", null, fixtures.resolve("v2"), fixtures);
        set(lenient, "failOnError", false);
        assertThatCode(lenient::execute).doesNotThrowAnyException();
    }

    @Test
    void skippedAndBadArguments() throws Exception {
        var skipped = mojo(null, null, null, fixtures.resolve("v2"), fixtures);
        set(skipped, "skip", true);
        assertThatCode(skipped::execute).doesNotThrowAnyException();
        assertThatThrownBy(() -> mojo(" ", null, null, fixtures.resolve("v2"), fixtures).execute())
                .isInstanceOf(MojoExecutionException.class).hasMessageContaining("-Dfrom");
        assertThatThrownBy(() -> mojo("v1", "v2", "nope.yaml", fixtures.resolve("v2"), fixtures).execute())
                .isInstanceOf(MojoExecutionException.class).hasMessageContaining("mapping file");
        assertThatThrownBy(() -> mojo("no-such-ref-or-path", "v2", null, fixtures.resolve("missing"), fixtures).execute())
                .isInstanceOf(MojoExecutionException.class).hasMessageContaining("neither a path");
    }

    @Test
    void aGitRefIsReadForEveryDefinitionFile(@TempDir Path repo) throws Exception {
        assumeTrue(CheckMigrationMojo.git(repo, "--version") != null, "git is not available");
        var workflows = Files.createDirectories(repo.resolve("src/main/resources/workflows"));
        Files.copy(fixtures.resolve("v1/order.json"), workflows.resolve("order.json"));
        Files.writeString(workflows.resolve("cron.yaml"), "schedules: []\n");
        assertThat(CheckMigrationMojo.git(repo, "init", "-q")).isNotNull();
        CheckMigrationMojo.git(repo, "config", "user.email", "ci@example.org");
        CheckMigrationMojo.git(repo, "config", "user.name", "CI");
        CheckMigrationMojo.git(repo, "config", "commit.gpgsign", "false");
        CheckMigrationMojo.git(repo, "add", ".");
        assertThat(CheckMigrationMojo.git(repo, "commit", "-q", "-m", "v1")).isNotNull();
        CheckMigrationMojo.git(repo, "tag", "v1");
        // The working copy moves on: the timer replaces the message wait.
        Files.copy(fixtures.resolve("v2-broken/order.json"), workflows.resolve("order.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThatThrownBy(() -> mojo("v1", null, null, workflows, repo).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("[wait-payment]");
        assertThatCode(() -> mojo("v1", "v1", null, workflows, repo).execute()).doesNotThrowAnyException();
        assertThatThrownBy(() -> mojo("v9", null, null, workflows, repo).execute())
                .isInstanceOf(MojoExecutionException.class).hasMessageContaining("nor a git ref");
    }
}
