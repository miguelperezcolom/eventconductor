package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Imports rule definitions from the configured Git repositories at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleGitImportRunner implements ApplicationRunner {

    final RuleGitImportProperties gitImportProperties;
    final ImportRulesFromGitUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (gitImportProperties.getRepositories().isEmpty()) {
            return;
        }
        log.info("Importing rule definitions from {} Git repository/ies…",
                gitImportProperties.getRepositories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("{} rule(s) imported: {}", result.imported().size(), result.imported());
        }
        if (!result.errors().isEmpty()) {
            log.warn("{} error(s) during rule import: {}", result.errors().size(), result.errors());
        }
    }
}
