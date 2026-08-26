package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase;
import io.mateu.workflow.infra.config.GitImportProperties;
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
 * Imports form definitions from Git repositories: clone, then hand the clone to
 * {@link ImportFormsFromDirectoryUseCase}, which is the import proper.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportFormsFromGitUseCase {

    final GitImportProperties gitImportProperties;
    final FormsMetrics formsMetrics;
    final ImportFormsFromDirectoryUseCase directoryImport;

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
                log.error("Failed to import from repository {}: {}", UrlSanitizer.sanitize(repo.getUrl()), e.getMessage(), e);
                errors.add("Repository " + UrlSanitizer.sanitize(repo.getUrl()) + ": " + e.getMessage());
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
            importFromClone(repo, tempDir, imported, errors, pruned);
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    /**
     * Everything after the clone — a clone is a directory, so from here on it is the same import.
     *
     * <p>Package-private so the scoping can be tested without cloning anything. That the configured
     * directory reaches this call at all is the whole of the bug this split exists to pin: the
     * field did not exist here, so the clone root and the bare URL went straight through and
     * `directory` changed nothing.
     */
    void importFromClone(GitImportProperties.GitRepository repo, Path cloneRoot,
                         List<String> imported, List<String> errors, List<String> pruned)
            throws IOException {
        directoryImport.importFrom(resolveScanRoot(repo, cloneRoot), pruneKey(repo),
                imported, errors, pruned);
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
        log.info("Cloning repository {} (branch: {}) into {}", UrlSanitizer.sanitize(repo.getUrl()), repo.getBranch(), targetDir);
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

    public record ImportFormsResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
