package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.taskcontractimport.ImportTasksFromDirectoryUseCase;
import io.mateu.workflow.infra.config.TaskContractDirectoryImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Imports task contracts from the configured directories at startup. Ordered ahead of the workflow
 * importers ({@code @Order(0)} and unordered) so a workflow step can resolve {@code task: <id>} to
 * the latest version as it loads.
 */
@Component
@Order(-100)
@RequiredArgsConstructor
@Slf4j
public class TaskContractDirectoryImportRunner implements ApplicationRunner {

    final TaskContractDirectoryImportProperties directoryImportProperties;
    final ImportTasksFromDirectoryUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (directoryImportProperties.getDirectories().isEmpty()) {
            log.debug("No directories configured for task-contract import — skipping.");
            return;
        }
        log.info("Starting task-contract import from {} director(y/ies)…",
                directoryImportProperties.getDirectories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("Imported {} task contract(s): {}", result.imported().size(), result.imported());
        }
        if (!result.errors().isEmpty()) {
            log.warn("Encountered {} error(s) during task-contract import: {}",
                    result.errors().size(), result.errors());
        }
    }
}
