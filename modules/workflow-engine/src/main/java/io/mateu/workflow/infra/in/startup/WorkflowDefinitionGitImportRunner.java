package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.infra.config.GitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowDefinitionGitImportRunner implements ApplicationRunner {

    final GitImportProperties gitImportProperties;
    final ImportWorkflowDefinitionsFromGitUseCase importUseCase;

    @Override
    public void run(ApplicationArguments args) {
        if (gitImportProperties.getRepositories().isEmpty()) {
            log.debug("No Git repositories configured for workflow definition import — skipping.");
            return;
        }
        log.info("Starting workflow definition import from {} Git repository/ies…",
                gitImportProperties.getRepositories().size());
        var result = importUseCase.handle();
        if (!result.imported().isEmpty()) {
            log.info("Imported {} workflow definition(s): {}", result.imported().size(), result.imported());
        }
        if (!result.errors().isEmpty()) {
            log.warn("Encountered {} error(s) during import: {}", result.errors().size(), result.errors());
        }
    }
}
