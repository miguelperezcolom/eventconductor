package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase;
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
public class FormDirectoryImportRunner implements ApplicationRunner {

    final DirectoryImportProperties directoryImportProperties;
    final ImportFormsFromDirectoryUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (directoryImportProperties.getDirectories().isEmpty()) {
            log.debug("No directories configured for form import — skipping.");
            return;
        }
        log.info("Starting form import from {} director(y/ies)…",
                directoryImportProperties.getDirectories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("Imported {} form(s): {}", result.imported().size(), result.imported());
        }
        if (!result.pruned().isEmpty()) {
            log.info("Pruned {} form(s): {}", result.pruned().size(), result.pruned());
        }
        if (!result.errors().isEmpty()) {
            log.warn("Encountered {} error(s) during import: {}", result.errors().size(), result.errors());
        }
    }
}
