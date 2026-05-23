package io.mateu.workflow.application.usecases.gitimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
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
public class ImportFormsFromGitUseCase {

    final GitImportProperties gitImportProperties;
    final FormRepository formRepository;
    final ObjectMapper objectMapper;
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public ImportFormsResult handle() {
        var imported = new ArrayList<String>();
        var errors   = new ArrayList<String>();

        for (var repo : gitImportProperties.getRepositories()) {
            try {
                importFromRepository(repo, imported, errors);
            } catch (Exception e) {
                log.error("Failed to import from repository {}: {}", repo.getUrl(), e.getMessage(), e);
                errors.add("Repository " + repo.getUrl() + ": " + e.getMessage());
            }
        }

        return new ImportFormsResult(imported, errors);
    }

    private void importFromRepository(GitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("forms-git-import-");
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

    private static boolean isDefinitionFile(Path path) {
        String name = path.toString();
        return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void scanAndImport(Path repoRoot, List<String> imported, List<String> errors)
            throws IOException {
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(ImportFormsFromGitUseCase::isDefinitionFile)
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
                : objectMapper.readTree(file.toFile());

        // Quick pre-check: must have "name" and "fields" to be a form definition.
        if (!node.has("name") || !node.has("fields")) {
            return;
        }

        var form = objectMapper.treeToValue(node, Form.class);

        // Assign an ID if missing.
        if (form.id() == null || form.id().isBlank()) {
            form = new Form(UUID.randomUUID().toString(), form.name(), form.description(), form.fields());
        }

        // Validation (schema + invariants) is handled inside formRepository.save().
        formRepository.save(form);
        log.info("Imported form '{}' (id={}) from {}", form.name(), form.id(), repoRoot.relativize(file));
        imported.add(form.name() + " [" + form.id() + "]");
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        var contents = dir.listFiles();
        if (contents != null) {
            for (var f : contents) deleteDirectory(f);
        }
        dir.delete();
    }

    public record ImportFormsResult(List<String> imported, List<String> errors) {}
}
