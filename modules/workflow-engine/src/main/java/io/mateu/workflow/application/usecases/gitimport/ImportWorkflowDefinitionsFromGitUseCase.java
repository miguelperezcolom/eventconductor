package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportWorkflowDefinitionsFromGitUseCase {

    /** Registry namespace so workflows, forms and rules can share one provenance store. */
    private static final String NAMESPACE = "workflow";

    final GitImportProperties gitImportProperties;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final WorkflowDefinitionValidator workflowDefinitionValidator;
    final ImportedDefinitionsRegistry importedDefinitionsRegistry;
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /** Re-imports every configured repository. */
    public ImportWorkflowDefinitionsResult handle() {
        return handle(gitImportProperties.getRepositories());
    }

    /** Re-imports the given subset of repositories (used by the webhook to reload only what changed). */
    public ImportWorkflowDefinitionsResult handle(List<GitImportProperties.GitRepository> repositories) {
        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var pruned = new ArrayList<String>();

        for (var repo : repositories) {
            try {
                importFromRepository(repo, imported, errors, pruned);
            } catch (Exception e) {
                log.error("Failed to import from repository {}: {}", repo.getUrl(), e.getMessage(), e);
                errors.add("Repository " + repo.getUrl() + ": " + e.getMessage());
            }
        }

        return new ImportWorkflowDefinitionsResult(imported, errors, pruned);
    }

    private void importFromRepository(GitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors,
                                      List<String> pruned) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("workflow-git-import-");
        try {
            cloneRepository(repo, tempDir);
            // Ids of the definitions that have an explicit, stable id in this repo — only these
            // can be reconciled across imports, so only these participate in pruning.
            var importedIds = new LinkedHashSet<String>();
            scanAndImport(tempDir, imported, errors, importedIds);
            pruneRemovedDefinitions(repo.getUrl(), importedIds, pruned);
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

    private static boolean isDefinitionFile(Path path) {
        return DefinitionFileFormat.isDefinitionFileName(path.toString());
    }

    void scanAndImport(Path repoRoot, List<String> imported, List<String> errors,
                               Set<String> importedIds) throws IOException {
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(ImportWorkflowDefinitionsFromGitUseCase::isDefinitionFile)
                    .forEach(file -> {
                        try {
                            importDefinitionFile(file, repoRoot, imported, importedIds);
                        } catch (Exception e) {
                            log.warn("Skipping {}: {}", file, e.getMessage());
                            errors.add("File " + repoRoot.relativize(file) + ": " + e.getMessage());
                        }
                    });
        }
    }

    private void importDefinitionFile(Path file, Path repoRoot, List<String> imported,
                                      Set<String> importedIds) throws IOException {
        String fileName = file.toString();
        var bytes = Files.readAllBytes(file);
        // .ec content may be JSON or YAML; sniff to pick the parser (.json/.yaml decide by extension).
        var node = DefinitionFileFormat.isYaml(fileName, bytes)
                ? YAML_MAPPER.readTree(bytes)
                : objectMapper.readTree(bytes);

        // Quick pre-check: must have both "name" and "steps" to be a workflow definition at all.
        if (!node.has("name") || !node.has("steps")) {
            return;
        }

        var definition = objectMapper.treeToValue(node, WorkflowDefinition.class);

        boolean hadExplicitId = definition.id() != null && !definition.id().isBlank();

        // Assign an ID if missing (schema marks it as optional).
        if (!hadExplicitId) {
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
                    definition.cronExpression(),
                    definition.defaultMaxStepExecutions(),
                    definition.steps()
            );
        }

        // Validation is delegated to WorkflowDefinitionValidator (called inside repository.save()).
        // Any violation will throw WorkflowDefinitionValidationException, caught by the caller.
        workflowDefinitionRepository.save(definition);
        // Only definitions with an explicit id can be reconciled on a later import (an
        // auto-generated id changes every time), so only those are prune-tracked.
        if (hadExplicitId) {
            importedIds.add(definition.id());
        }
        log.info("Imported workflow definition '{}' (id={}) from {}",
                definition.name(), definition.id(), repoRoot.relativize(file));
        imported.add(definition.name() + " [" + definition.id() + "]");
    }

    /**
     * Archives definitions previously imported from this repository that are no longer present
     * (removed or renamed in the repo). Scoped by the registry to git-imported definitions, so
     * classpath and hand-authored definitions are never touched. Archive (not delete) keeps it
     * reversible and never fights the "cannot delete an ACTIVE definition" guard.
     */
    void pruneRemovedDefinitions(String repositoryUrl, Set<String> importedIds, List<String> pruned) {
        var previous = importedDefinitionsRegistry.idsFor(NAMESPACE, repositoryUrl);
        for (var id : previous) {
            if (importedIds.contains(id)) {
                continue;
            }
            workflowDefinitionRepository.findById(id).ifPresent(def -> {
                if (def.status() != WorkflowDefinitionStatus.ARCHIVED) {
                    workflowDefinitionRepository.save(def.withStatus(WorkflowDefinitionStatus.ARCHIVED));
                    log.info("Pruned (archived) workflow definition '{}' (id={}) — no longer in {}",
                            def.name(), id, repositoryUrl);
                    pruned.add(def.name() + " [" + id + "]");
                }
            });
        }
        importedDefinitionsRegistry.replace(NAMESPACE, repositoryUrl, importedIds);
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

    public record ImportWorkflowDefinitionsResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
