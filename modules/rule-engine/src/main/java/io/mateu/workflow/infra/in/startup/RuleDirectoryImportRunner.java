package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Imports rules from the configured directories at startup, as the other two engines do. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleDirectoryImportRunner implements ApplicationRunner {

    final RuleDirectoryImportProperties directoryImportProperties;
    final ImportRulesFromDirectoryUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (directoryImportProperties.getDirectories().isEmpty()) {
            log.debug("No directories configured for rule import — skipping.");
            return;
        }
        log.info("Starting rule import from {} director(y/ies)…",
                directoryImportProperties.getDirectories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("Imported {} rule(s): {}", result.imported().size(), result.imported());
        }
        if (!result.pruned().isEmpty()) {
            log.info("Pruned {} rule(s): {}", result.pruned().size(), result.pruned());
        }
        if (!result.errors().isEmpty()) {
            log.warn("Encountered {} error(s) during import: {}", result.errors().size(), result.errors());
        }
    }
}
