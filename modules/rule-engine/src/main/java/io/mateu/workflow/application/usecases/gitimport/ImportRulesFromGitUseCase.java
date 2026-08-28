package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.application.usecases.directoryimport.ImportRulesFromDirectoryUseCase;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportRulesFromGitUseCase {

    final RuleGitImportProperties gitImportProperties;
    final RuleCatalogMetrics ruleCatalogMetrics;
    /** A clone is a directory; everything after the clone is that import, and only that one. */
    final ImportRulesFromDirectoryUseCase directoryImport;
    /** Re-imports every configured repository. */
    public ImportRulesFromDirectoryUseCase.ImportRulesResult handle() {
        return handle(gitImportProperties.getRepositories());
    }

    /** Re-imports the given subset of repositories (used by the webhook to reload only what changed). */
    public ImportRulesFromDirectoryUseCase.ImportRulesResult handle(List<RuleGitImportProperties.GitRepository> repositories) {
        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var pruned = new ArrayList<String>();

        for (var repo : repositories) {
            try {
                importFromRepository(repo, imported, errors, pruned);
            } catch (Exception e) {
                log.error("Failed to import from repository {}: {}", UrlSanitizer.sanitize(repo.getUrl()), e.getMessage(), e);
                errors.add("Repository " + UrlSanitizer.sanitize(repo.getUrl()) + ": " + e.getMessage());
            }
        }

        ruleCatalogMetrics.rulesImported(imported.size());
        return new ImportRulesFromDirectoryUseCase.ImportRulesResult(imported, errors, pruned);
    }

    private void importFromRepository(RuleGitImportProperties.GitRepository repo,
                                      List<String> imported,
                                      List<String> errors,
                                      List<String> pruned) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("rules-git-import-");
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
     * <p>Package-private so the scoping can be tested without cloning anything, as in the forms
     * import this now mirrors.
     */
    void importFromClone(RuleGitImportProperties.GitRepository repo, Path cloneRoot,
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

}
