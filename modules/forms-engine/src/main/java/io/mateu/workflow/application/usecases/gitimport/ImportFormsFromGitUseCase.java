package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.Form;
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
public class ImportFormsFromGitUseCase {

    /** Registry namespace so workflows, forms and rules can share one provenance store. */
    private static final String NAMESPACE = "form";

    final GitImportProperties gitImportProperties;
    final FormRepository formRepository;
    final FormsMetrics formsMetrics;
    final ImportedDefinitionsRegistry importedDefinitionsRegistry;
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /** Re-imports every configured repository. */
    public ImportFormsResult handle() {
        return handle(gitImportProperties.getRepositories());
    }

    /** Re-imports the given subset of repositories (used by the webhook to reload only what changed). */
    public ImportFormsResult handle(List<GitImportProperties.GitRepository> repositories) {
        var imported = new ArrayList<String>();
        var errors   = new ArrayList<String>();
        var pruned   = new ArrayList<String>();

        for (var repo : repositories) {
            try {
                importFromRepository(repo, imported, errors, pruned);
            } catch (Exception e) {
                log.error("Failed to import from repository {}: {}", repo.getUrl(), e.getMessage(), e);
                errors.add("Repository " + repo.getUrl() + ": " + e.getMessage());
            }
        }

        formsMetrics.formsImported(imported.size());

        return new ImportFormsResult(imported, errors, pruned);
    }

    private void importFromRepository(GitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors,
                                      List<String> pruned) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("forms-git-import-");
        try {
            cloneRepository(repo, tempDir);
            var importedIds = new LinkedHashSet<String>();
            scanAndImport(tempDir, imported, errors, importedIds);
            pruneRemovedForms(repo.getUrl(), importedIds, pruned);
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
        String name = path.toString();
        return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void scanAndImport(Path repoRoot, List<String> imported, List<String> errors,
                               Set<String> importedIds) throws IOException {
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(ImportFormsFromGitUseCase::isDefinitionFile)
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
        var node = (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))
                ? YAML_MAPPER.readTree(file.toFile())
                : objectMapper.readTree(file.toFile());

        // Quick pre-check: must have "name" and "fields" to be a form definition.
        if (!node.has("name") || !node.has("fields")) {
            return;
        }

        var form = objectMapper.treeToValue(node, Form.class);

        boolean hadExplicitId = form.id() != null && !form.id().isBlank();
        if (!hadExplicitId) {
            form = new Form(UUID.randomUUID().toString(), form.name(), form.description(), form.fields());
        }

        // Validation (schema + invariants) is handled inside formRepository.save().
        formRepository.save(form);
        // Only forms with an explicit id can be reconciled on a later import, so only those
        // are prune-tracked.
        if (hadExplicitId) {
            importedIds.add(form.id());
        }
        log.info("Imported form '{}' (id={}) from {}", form.name(), form.id(), repoRoot.relativize(file));
        imported.add(form.name() + " [" + form.id() + "]");
    }

    /**
     * Deletes forms previously imported from this repository that are no longer present.
     * Scoped by the registry to git-imported forms, so classpath and hand-authored forms are
     * never touched. Forms have no lifecycle status, so pruning removes them (unlike workflow
     * definitions, which are archived); a form carries no running state, so this is safe.
     */
    void pruneRemovedForms(String repositoryUrl, Set<String> importedIds, List<String> pruned) {
        var previous = importedDefinitionsRegistry.idsFor(NAMESPACE, repositoryUrl);
        for (var id : previous) {
            if (importedIds.contains(id)) {
                continue;
            }
            formRepository.findById(id).ifPresent(form -> {
                formRepository.deleteAllById(List.of(id));
                log.info("Pruned (deleted) form '{}' (id={}) — no longer in {}", form.name(), id, repositoryUrl);
                pruned.add(form.name() + " [" + id + "]");
            });
        }
        importedDefinitionsRegistry.replace(NAMESPACE, repositoryUrl, importedIds);
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        var contents = dir.listFiles();
        if (contents != null) {
            for (var f : contents) deleteDirectory(f);
        }
        dir.delete();
    }

    public record ImportFormsResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
