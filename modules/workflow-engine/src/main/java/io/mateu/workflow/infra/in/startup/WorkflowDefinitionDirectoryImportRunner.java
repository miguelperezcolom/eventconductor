package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Imports the configured local directories at startup, the way the git runner imports repositories. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowDefinitionDirectoryImportRunner implements ApplicationRunner {

    final DirectoryImportProperties directoryImportProperties;
    final ImportWorkflowDefinitionsFromDirectoryUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (directoryImportProperties.getDirectories().isEmpty()) {
            log.debug("No directories configured for workflow definition import — skipping.");
            return;
        }
        log.info("Starting workflow definition import from {} director(y/ies)…",
                directoryImportProperties.getDirectories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("Imported {} workflow definition(s): {}", result.imported().size(), result.imported());
        }
        if (!result.pruned().isEmpty()) {
            log.info("Pruned {} workflow definition(s): {}", result.pruned().size(), result.pruned());
        }
        if (!result.errors().isEmpty()) {
            log.warn("Encountered {} error(s) during import: {}", result.errors().size(), result.errors());
        }
    }
}
