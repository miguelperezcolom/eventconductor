package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
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
 * Imports workflow definitions from Git repositories: clone, then hand the clone to
 * {@link ImportWorkflowDefinitionsFromDirectoryUseCase}, which is the import proper.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportWorkflowDefinitionsFromGitUseCase {

    final GitImportProperties gitImportProperties;
    final ImportWorkflowDefinitionsFromDirectoryUseCase directoryImport;

    /**
     * Where clones are made; null = the system temp directory. A test points it at a directory of its
     * own, so it can check that every clone is removed without racing other builds on the machine that
     * use the shared temp directory too.
     */
    Path cloneRoot;

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
                // JGit puts the URL it failed on in the message, user name included — and a token is
                // often passed as the user name — so the message is sanitized as the URL is, and the
                // exception itself (which carries it unsanitized) only reaches the debug log.
                var message = UrlSanitizer.sanitize(e.getMessage());
                log.error("Failed to import from repository {}: {}", UrlSanitizer.sanitize(repo.getUrl()), message);
                log.debug("Import failure detail", e);
                errors.add("Repository " + UrlSanitizer.sanitize(repo.getUrl()) + ": " + message);
            }
        }

        return new ImportWorkflowDefinitionsResult(imported, errors, pruned);
    }

    private void importFromRepository(GitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors,
                                      List<String> pruned) throws IOException, GitAPIException {
        Path tempDir = cloneRoot == null
                ? Files.createTempDirectory("workflow-git-import-")
                : Files.createTempDirectory(cloneRoot, "workflow-git-import-");
        try {
            cloneRepository(repo, tempDir);
            // A clone is a directory, so from here on it is the same import.
            directoryImport.importFrom(resolveScanRoot(repo, tempDir), pruneKey(repo), formerPruneKey(repo),
                    imported, errors, pruned);
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
        // Sanitized: the key is persisted (provenance, schedule and trigger sources) and shown in the
        // UI, REST and MCP, so credentials embedded in the URL (https://user:token@host/…) must not be in it.
        return keyOf(UrlSanitizer.sanitize(repo.getUrl()), repo.getDirectory());
    }

    /**
     * The key this source had before it was sanitized — the raw URL — whose rows the next import moves
     * over and empties. Equal to {@link #pruneKey} for a URL without credentials.
     */
    static String formerPruneKey(GitImportProperties.GitRepository repo) {
        return keyOf(repo.getUrl(), repo.getDirectory());
    }

    private static String keyOf(String url, String directory) {
        if (directory == null || directory.isBlank()) {
            return url;
        }
        return url + "#" + directory;
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
            for (var f : contents) {
                deleteDirectory(f);
            }
        }
        dir.delete();
    }

    public record ImportWorkflowDefinitionsResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
