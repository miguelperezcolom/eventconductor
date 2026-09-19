package io.mateu.workflow.application.usecases.taskcontractimport;

import io.mateu.workflow.infra.config.TaskContractGitImportProperties;
import io.mateu.workflow.webhook.UrlSanitizer;
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

/**
 * Imports task contracts from Git repositories: a clone followed by the directory import, exactly
 * as the workflow, form and rule git imports are. No pruning — see
 * {@link ImportTasksFromDirectoryUseCase}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportTasksFromGitUseCase {

    final TaskContractGitImportProperties gitImportProperties;
    final ImportTasksFromDirectoryUseCase directoryImport;

    /** Re-imports every configured repository. */
    public ImportTasksFromDirectoryUseCase.ImportTasksResult handle() {
        return handle(gitImportProperties.getRepositories());
    }

    /** Re-imports the given subset of repositories. */
    public ImportTasksFromDirectoryUseCase.ImportTasksResult handle(
            List<TaskContractGitImportProperties.GitRepository> repositories) {
        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        for (var repo : repositories) {
            try {
                importFromRepository(repo, imported, errors);
            } catch (Exception e) {
                log.error("Failed to import task contracts from repository {}: {}",
                        UrlSanitizer.sanitize(repo.getUrl()), e.getMessage(), e);
                errors.add("Repository " + UrlSanitizer.sanitize(repo.getUrl()) + ": " + e.getMessage());
            }
        }
        return new ImportTasksFromDirectoryUseCase.ImportTasksResult(imported, errors);
    }

    private void importFromRepository(TaskContractGitImportProperties.GitRepository repo,
                                      List<String> imported, List<String> errors)
            throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("tasks-git-import-");
        try {
            cloneRepository(repo, tempDir);
            directoryImport.importFrom(resolveScanRoot(repo, tempDir), imported, errors);
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    /** The directory to scan: the repo root, or the configured subdirectory, rejecting escapes. */
    Path resolveScanRoot(TaskContractGitImportProperties.GitRepository repo, Path repoRoot) throws IOException {
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

    private void cloneRepository(TaskContractGitImportProperties.GitRepository repo, Path targetDir)
            throws GitAPIException {
        log.info("Cloning repository {} (branch: {}) into {}",
                UrlSanitizer.sanitize(repo.getUrl()), repo.getBranch(), targetDir);
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

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        var contents = dir.listFiles();
        if (contents != null) {
            for (var f : contents) deleteDirectory(f);
        }
        dir.delete();
    }
}
