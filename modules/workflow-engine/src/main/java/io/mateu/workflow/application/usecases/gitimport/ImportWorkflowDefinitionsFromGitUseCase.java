package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.GitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportWorkflowDefinitionsFromGitUseCase {

    final GitImportProperties gitImportProperties;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final WorkflowDefinitionValidator workflowDefinitionValidator;
    final ObjectMapper objectMapper;

    public ImportWorkflowDefinitionsResult handle() {
        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();

        for (var repo : gitImportProperties.getRepositories()) {
            try {
                importFromRepository(repo, imported, errors);
            } catch (Exception e) {
                log.error("Failed to import from repository {}: {}", repo.getUrl(), e.getMessage(), e);
                errors.add("Repository " + repo.getUrl() + ": " + e.getMessage());
            }
        }

        return new ImportWorkflowDefinitionsResult(imported, errors);
    }

    private void importFromRepository(GitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("workflow-git-import-");
        try {
            cloneRepository(repo, tempDir);
            scanAndImport(tempDir, imported, errors);
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private void cloneRepository(GitImportProperties.GitRepository repo, Path targetDir)
            throws GitAPIException {
        log.info("Cloning repository {} (branch: {}) into {}", repo.getUrl(), repo.getBranch(), targetDir);
        var cloneCommand = Git.cloneRepository()
                .setURI(repo.getUrl())
                .setDirectory(targetDir.toFile())
                .setBranch(repo.getBranch());

        if (repo.getUsername() != null && !repo.getUsername().isBlank()) {
            cloneCommand.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(repo.getUsername(), repo.getPassword()));
        }

        cloneCommand.call().close();
        log.info("Repository cloned successfully");
    }

    private void scanAndImport(Path repoRoot, List<String> imported, List<String> errors) throws IOException {
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(path -> path.toString().endsWith(".json"))
                    .forEach(jsonFile -> {
                        try {
                            importJsonFile(jsonFile, repoRoot, imported);
                        } catch (Exception e) {
                            log.warn("Skipping {}: {}", jsonFile, e.getMessage());
                            errors.add("File " + repoRoot.relativize(jsonFile) + ": " + e.getMessage());
                        }
                    });
        }
    }

    private void importJsonFile(Path jsonFile, Path repoRoot, List<String> imported) throws IOException {
        var node = objectMapper.readTree(jsonFile.toFile());

        // Quick pre-check: must have both "name" and "steps" to be a workflow definition at all.
        if (!node.has("name") || !node.has("steps")) {
            return;
        }

        var definition = objectMapper.treeToValue(node, WorkflowDefinition.class);

        // Assign an ID if missing (schema marks it as optional).
        if (definition.id() == null || definition.id().isBlank()) {
            definition = new WorkflowDefinition(
                    UUID.randomUUID().toString(),
                    definition.name(),
                    definition.version(),
                    definition.description(),
                    definition.status(),
                    null,
                    definition.limitConcurrentExecutions(),
                    definition.maxConcurrentExecutions(),
                    definition.enqueueOnLimit(),
                    definition.steps()
            );
        }

        // Validation is delegated to WorkflowDefinitionValidator (called inside repository.save()).
        // Any violation will throw WorkflowDefinitionValidationException, caught by the caller.
        workflowDefinitionRepository.save(definition);
        log.info("Imported workflow definition '{}' (id={}) from {}",
                definition.name(), definition.id(), repoRoot.relativize(jsonFile));
        imported.add(definition.name() + " [" + definition.id() + "]");
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        var contents = dir.listFiles();
        if (contents != null) {
            for (var f : contents) {
                deleteDirectory(f);
            }
        }
        dir.delete();
    }

    public record ImportWorkflowDefinitionsResult(List<String> imported, List<String> errors) {}
}
