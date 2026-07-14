package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
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
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportRulesFromGitUseCase {

    private static final Set<String> RULE_TYPES = Set.of("expression", "decision-table");

    final RuleGitImportProperties gitImportProperties;
    final SaveRuleUseCase saveRuleUseCase;
    // Own mapper: headless embedders may not expose an ObjectMapper bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public ImportRulesResult handle() {
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

        return new ImportRulesResult(imported, errors);
    }

    private void importFromRepository(RuleGitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("rules-git-import-");
        try {
            cloneRepository(repo, tempDir);
            scanAndImport(tempDir, imported, errors);
        } finally {
            deleteDirectory(tempDir.toFile());
        }
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

    private void scanAndImport(Path repoRoot, List<String> imported, List<String> errors)
            throws IOException {
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(ImportRulesFromGitUseCase::isDefinitionFile)
                    .forEach(file -> {
                        try {
                            importDefinitionFile(file, repoRoot, imported);
                        } catch (Exception e) {
                            log.warn("Skipping {}: {}", file, e.getMessage());
                            errors.add("File " + repoRoot.relativize(file) + ": " + e.getMessage());
                        }
                    });
        }
    }

    private void importDefinitionFile(Path file, Path repoRoot, List<String> imported)
            throws IOException {
        String fileName = file.toString();
        var node = (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))
                ? YAML_MAPPER.readTree(file.toFile())
                : OBJECT_MAPPER.readTree(file.toFile());

        // Quick pre-check: must have "name" and a rule "type" to be a rule definition.
        if (!node.has("name") || !node.has("type") || !RULE_TYPES.contains(node.get("type").asText())) {
            return;
        }

        var rule = OBJECT_MAPPER.treeToValue(node, Rule.class);

        // Validation, id assignment and publication happen inside the save use case.
        var id = saveRuleUseCase.handle(new SaveRuleCommand(rule));
        log.info("Imported rule '{}' (id={}) from {}", rule.name(), id, repoRoot.relativize(file));
        imported.add(rule.name() + " [" + id + "]");
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        var contents = dir.listFiles();
        if (contents != null) {
            for (var f : contents) deleteDirectory(f);
        }
        dir.delete();
    }

    public record ImportRulesResult(List<String> imported, List<String> errors) {}
}
