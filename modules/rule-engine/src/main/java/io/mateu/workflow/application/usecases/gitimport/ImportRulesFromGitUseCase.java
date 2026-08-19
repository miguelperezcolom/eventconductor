package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportRulesFromGitUseCase {

    private static final Set<String> RULE_TYPES = Set.of("expression", "decision-table");

    /** Registry namespace so workflows, forms and rules can share one provenance store. */
    private static final String NAMESPACE = "rule";

    final RuleGitImportProperties gitImportProperties;
    final SaveRuleUseCase saveRuleUseCase;
    final RuleRepository ruleRepository;
    final RuleCatalogMetrics ruleCatalogMetrics;
    final ImportedDefinitionsRegistry importedDefinitionsRegistry;
    // Own mapper: headless embedders may not expose an ObjectMapper bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /** Re-imports every configured repository. */
    public ImportRulesResult handle() {
        return handle(gitImportProperties.getRepositories());
    }

    /** Re-imports the given subset of repositories (used by the webhook to reload only what changed). */
    public ImportRulesResult handle(List<RuleGitImportProperties.GitRepository> repositories) {
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

        ruleCatalogMetrics.rulesImported(imported.size());
        return new ImportRulesResult(imported, errors, pruned);
    }

    private void importFromRepository(RuleGitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors,
                                      List<String> pruned) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("rules-git-import-");
        try {
            cloneRepository(repo, tempDir);
            var importedIds = new LinkedHashSet<String>();
            scanAndImport(resolveScanRoot(repo, tempDir), imported, errors, importedIds);
            pruneRemovedRules(pruneKey(repo), importedIds, pruned);
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
    Path resolveScanRoot(RuleGitImportProperties.GitRepository repo, Path repoRoot) throws IOException {
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
    static String pruneKey(RuleGitImportProperties.GitRepository repo) {
        String directory = repo.getDirectory();
        if (directory == null || directory.isBlank()) {
            return repo.getUrl();
        }
        return repo.getUrl() + "#" + directory;
    }

    private void cloneRepository(RuleGitImportProperties.GitRepository repo, Path targetDir)
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
            stream.filter(ImportRulesFromGitUseCase::isDefinitionFile)
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
                : OBJECT_MAPPER.readTree(file.toFile());

        // Quick pre-check: must have "name" and a rule "type" to be a rule definition.
        if (!node.has("name") || !node.has("type") || !RULE_TYPES.contains(node.get("type").asText())) {
            return;
        }

        var rule = OBJECT_MAPPER.treeToValue(node, Rule.class);

        boolean hadExplicitId = rule.id() != null && !rule.id().isBlank();

        // Validation, id assignment and publication happen inside the save use case.
        var id = saveRuleUseCase.handle(new SaveRuleCommand(rule));
        // Only rules with an explicit id can be reconciled on a later import (the returned id
        // equals the file's id in that case), so only those are prune-tracked.
        if (hadExplicitId) {
            importedIds.add(id);
        }
        log.info("Imported rule '{}' (id={}) from {}", rule.name(), id, repoRoot.relativize(file));
        imported.add(rule.name() + " [" + id + "]");
    }

    /**
     * Deletes rules previously imported from this repository that are no longer present.
     * Scoped by the registry to git-imported rules, so classpath and hand-authored rules are
     * never touched. Rules have no lifecycle status, so pruning removes them (unlike workflow
     * definitions, which are archived).
     */
    void pruneRemovedRules(String repositoryUrl, Set<String> importedIds, List<String> pruned) {
        var previous = importedDefinitionsRegistry.idsFor(NAMESPACE, repositoryUrl);
        for (var id : previous) {
            if (importedIds.contains(id)) {
                continue;
            }
            ruleRepository.findById(id).ifPresent(rule -> {
                ruleRepository.deleteAllById(List.of(id));
                log.info("Pruned (deleted) rule '{}' (id={}) — no longer in {}", rule.name(), id, repositoryUrl);
                pruned.add(rule.name() + " [" + id + "]");
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

    public record ImportRulesResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
