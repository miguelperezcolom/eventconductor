package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.services.CronScheduleImporter;
import io.mateu.workflow.application.services.TaskReferenceResolver;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.SchedulePause;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.infra.out.classpath.ClasspathTaskContractRepository;
import io.mateu.workflow.infra.out.classpath.ClasspathWorkflowDefinitionRepository;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The key a git source is recorded under is persisted and shown, so it never carries the credentials
 * a repository URL can embed — and what an older import recorded under the raw URL moves over.
 */
class GitImportSourceKeyTest {

    private static final String RAW = "https://ci-bot:fake-test-token@github.com/org/defs.git";

    private static GitImportProperties.GitRepository repo(String url, String directory) {
        var repo = new GitImportProperties.GitRepository();
        repo.setUrl(url);
        repo.setDirectory(directory);
        return repo;
    }

    @Test
    void theSourceKeyCarriesNoCredentials() {
        assertThat(ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo(RAW, null)))
                .isEqualTo("https://******@github.com/org/defs.git").doesNotContain("fake-test-token");
        assertThat(ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo("https://fake-test-token-2@github.com/org/defs.git", "flows")))
                .isEqualTo("https://******@github.com/org/defs.git#flows");
        assertThat(ImportWorkflowDefinitionsFromGitUseCase.formerPruneKey(repo(RAW, null))).isEqualTo(RAW);
        assertThat(ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo("https://github.com/org/defs.git", null)))
                .isEqualTo(ImportWorkflowDefinitionsFromGitUseCase.formerPruneKey(repo("https://github.com/org/defs.git", null)));
    }

    @Test
    void schedulesRecordedUnderTheRawUrlMoveToTheSanitizedKeyKeepingTheirPause(@TempDir Path dir) throws Exception {
        var resolver = new TaskReferenceResolver(new ClasspathTaskContractRepository());
        var definitions = new ClasspathWorkflowDefinitionRepository(resolver);
        var schedules = new InMemoryCronScheduleRepository();
        var pauses = new InMemorySchedulePauseRepository();
        var directoryImport = new ImportWorkflowDefinitionsFromDirectoryUseCase(mock(DirectoryImportProperties.class),
                definitions, new InMemoryImportedDefinitionsRegistry(), new WorkflowDefinitionValidator(), resolver,
                new CronScheduleImporter(definitions, schedules, pauses));
        Files.writeString(dir.resolve("report.yaml"), """
                id: report
                name: report
                version: 1
                steps:
                  - id: start
                    type: START
                    name: Start
                """);
        Files.writeString(dir.resolve("cron.yaml"), "- id: nightly\n  cron: \"@daily\"\n  workflow: report\n");
        // What an import before this change left behind: the source recorded under the raw URL, paused.
        directoryImport.importFrom(dir, RAW, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        pauses.save(new SchedulePause("nightly", Instant.parse("2026-10-01T00:00:00Z"), "ops"));

        var key = ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo(RAW, null));
        directoryImport.importFrom(dir, key, RAW, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        assertThat(schedules.findAll()).containsExactly(new CronFileSchedule(key, 0, "report",
                new CronSchedule("nightly", "@daily", null, null)));
        assertThat(schedules.findAll()).noneMatch(s -> s.source().contains("fake-test-token"));
        assertThat(pauses.findAll()).containsKey("nightly");
    }
}
