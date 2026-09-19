package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.taskcontractimport.ImportTasksFromGitUseCase;
import io.mateu.workflow.infra.config.TaskContractGitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Imports task contracts from the configured Git repositories at startup, ahead of the workflow
 * importers so their steps can resolve task references to the latest version.
 */
@Component
@Order(-100)
@RequiredArgsConstructor
@Slf4j
public class TaskContractGitImportRunner implements ApplicationRunner {

    final TaskContractGitImportProperties gitImportProperties;
    final ImportTasksFromGitUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (gitImportProperties.getRepositories().isEmpty()) {
            return;
        }
        log.info("Importing task contracts from {} Git repository/ies…",
                gitImportProperties.getRepositories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("{} task contract(s) imported: {}", result.imported().size(), result.imported());
        }
        if (!result.errors().isEmpty()) {
            log.warn("{} error(s) during task-contract import: {}", result.errors().size(), result.errors());
        }
    }
}
