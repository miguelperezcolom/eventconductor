package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
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
            scanAndImport(resolveScanRoot(repo, tempDir), imported, errors, importedIds);
            pruneRemovedDefinitions(pruneKey(repo), importedIds, pruned);
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    /**
     * Resolves the directory to scan: the repo root, or the configured subdirectory when set.
     * The path is resolved and normalized against the clone root; a directory that escapes the
     * repo (e.g. "../etc") or does not exist is rejected.
     */
    /** Package-private so the escape guard below can be tested without cloning anything. */
    Path resolveScanRoot(GitImportProperties.GitRepository repo, Path repoRoot) throws IOException {
        String directory = repo.getDirectory();
        if (directory == null || directory.isBlank()) {
            return repoRoot;
        }
        Path scanRoot = repoRoot.resolve(directory).normalize();
        if (!scanRoot.startsWith(repoRoot)) {
            throw new IOException("directory '" + directory + "' escapes the repository root");
        }
        if (!Files.isDirectory(scanRoot)) {
            throw new IOException("directory '" + directory + "' not found in repository");
        }
        return scanRoot;
    }

    /**
     * Prune scope key. Two repository entries can share a URL but point at different
     * subdirectories, so the directory is folded into the key to keep their provenance
     * — and therefore pruning — independent.
     */
    static String pruneKey(GitImportProperties.GitRepository repo) {
        String directory = repo.getDirectory();
        if (directory == null || directory.isBlank()) {
            return repo.getUrl();
        }
        return repo.getUrl() + "#" + directory;
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

        adoptLegacyLifecycleFields(node, fileName);

        var definition = objectMapper.treeToValue(node, WorkflowDefinition.class);

        boolean hadExplicitId = definition.id() != null && !definition.id().isBlank();

        // Assign an ID if missing (schema marks it as optional).
        if (!hadExplicitId) {
            definition = new WorkflowDefinition(
                    UUID.randomUUID().toString(),
                    definition.name(),
                    definition.version(),
                    definition.description(),
                    definition.limitConcurrentExecutions(),
                    definition.maxConcurrentExecutions(),
                    definition.enqueueOnLimit(),
                    definition.cronExpression(),
                    definition.defaultMaxStepExecutions(),
                    definition.steps()
            );
        }

        // What the file says about being disabled or archived is a declaration, not a runtime
        // flag: it belongs to whoever writes the workflow, and it is a floor the runtime cannot
        // lift. The runtime flags belong to whoever operates it, so they are taken from the row
        // that is already there — overwriting them, which is what this used to do, meant every
        // import silently put back into service anything an operator had taken out.
        definition = asDeclaration(definition);
        var existing = workflowDefinitionRepository.findById(definition.id());
        if (existing.isPresent()) {
            definition = definition.withRuntimeStateOf(existing.get());
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
     * Reads the older ways a file could say it is not to run — the `disabled` and `archived`
     * booleans — and leaves a single `status` behind.
     *
     * <p>They said between them what one word says: four combinations for three meanings, with
     * "is an archived workflow also disabled?" answered in prose. Files written against them keep
     * working, and the value they meant survives; the field they used does not.
     *
     * <p>A legacy DRAFT or ACTIVE `status`, which an older graph editor wrote and the engine never
     * had, means nothing and is dropped — the values that did mean something, DISABLED and
     * ARCHIVED, are now what this field is for.
     */
    static void adoptLegacyLifecycleFields(JsonNode node, String fileName) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        var declared = object.hasNonNull("status") ? object.get("status").asText("") : null;
        var disabled = object.path("disabled").asBoolean(false);
        var archived = object.path("archived").asBoolean(false);
        if (!object.has("disabled") && !object.has("archived")
                && (declared == null || WorkflowStatus.of(declared, false, false).name().equalsIgnoreCase(declared))) {
            return;   // nothing legacy about it
        }
        object.remove("disabled");
        object.remove("archived");
        var status = WorkflowStatus.of(declared, disabled, archived);
        object.put("status", status.name());
        log.info("{} uses the older lifecycle fields (status={}, disabled={}, archived={}); read as"
                + " status {}. Re-save it from the editor to write it directly.",
                fileName, declared, disabled, archived, status);
    }

    /**
     * Moves what the file declared — {@code disabled}, {@code archived} — out of the runtime flags
     * and into the declaration, leaving the runtime ones clear for the caller to fill from the
     * existing row.
     */
    private static WorkflowDefinition asDeclaration(WorkflowDefinition fromFile) {
        return new WorkflowDefinition(
                fromFile.id(), fromFile.name(), fromFile.version(), fromFile.description(),
                fromFile.limitConcurrentExecutions(), fromFile.maxConcurrentExecutions(),
                fromFile.enqueueOnLimit(), fromFile.cronExpression(),
                fromFile.defaultMaxStepExecutions(), fromFile.steps(),
                false, fromFile.declaredStatus(), WorkflowStatus.ACTIVE);
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
                if (!def.archived()) {
                    workflowDefinitionRepository.save(def.withArchived(true));
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
