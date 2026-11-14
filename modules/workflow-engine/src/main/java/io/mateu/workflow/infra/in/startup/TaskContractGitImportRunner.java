package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.taskcontractimport.ImportTasksFromGitUseCase;
import io.mateu.workflow.infra.config.TaskContractGitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Imports task contracts from the configured Git repositories at startup, ahead of the workflow
 * importers so their steps can resolve task references to the latest version — including the
 * classpath and directory ones, which is why it is a {@link StartupDefinitionImport} (run before the
 * web server starts) although it reads a remote repository.
 */
@Component
@Order(-100)
@RequiredArgsConstructor
@Slf4j
public class TaskContractGitImportRunner implements StartupDefinitionImport {

    final TaskContractGitImportProperties gitImportProperties;
    final ImportTasksFromGitUseCase importUseCase;

    @Override
    public void importDefinitions() {
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
