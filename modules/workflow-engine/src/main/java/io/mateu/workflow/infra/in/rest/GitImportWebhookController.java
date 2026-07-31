package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.GitPushPayload;
import io.mateu.workflow.webhook.GitPushPayloadParser;
import io.mateu.workflow.webhook.RepositoryUrlMatcher;
import io.mateu.workflow.webhook.WebhookProvider;
import io.mateu.workflow.webhook.WebhookSignatureVerifier;
import io.mateu.workflow.webhook.WebhookSignatureVerifier.WebhookVerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Webhook endpoint triggered by a git provider after a push/merge.
 * {@code POST /workflow/webhooks/{provider}} — re-imports the matching configured repositories.
 *
 * <p>{@code provider} is one of {@code github}, {@code gitlab}, {@code bitbucket} or
 * {@code generic} (unknown values are treated as generic). {@code /github} keeps the original
 * behaviour. The payload is parsed to reload only the repository and branch that changed;
 * a push to a repository/branch that no configuration cares about is acknowledged and ignored.
 *
 * <pre>
 *   workflow:
 *     git-import:
 *       webhook-secret: mysecret          # GitHub/Bitbucket HMAC, GitLab/generic token
 *       repositories:
 *         - url: https://github.com/org/workflow-defs.git
 *           branch: master
 * </pre>
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/webhooks")
@RequiredArgsConstructor
@Slf4j
public class GitImportWebhookController {

    final GitImportProperties gitImportProperties;
    final ImportWorkflowDefinitionsFromGitUseCase importUseCase;

    /**
     * Provider-agnostic webhook receiver. Responds 202 immediately and runs the import in the
     * background so a provider's short delivery timeout is never hit, even for large repos.
     */
    @PostMapping("/{provider}")
    public ResponseEntity<String> webhook(
            @PathVariable String provider,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] body) {

        var webhookProvider = WebhookProvider.fromPath(provider);
        var payloadBytes = body == null ? new byte[0] : body;

        try {
            WebhookSignatureVerifier.verify(webhookProvider, gitImportProperties.getWebhookSecret(),
                    headers::getFirst, payloadBytes);
        } catch (WebhookVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        var repositories = gitImportProperties.getRepositories();
        if (repositories.isEmpty()) {
            log.info("Webhook received but no Git repositories configured — nothing to import.");
            return ResponseEntity.accepted().body("no repositories configured");
        }

        var payload = GitPushPayloadParser.parse(webhookProvider, payloadBytes);
        var selected = selectRepositories(repositories, payload);

        if (selected.isEmpty() && !payload.isEmpty()) {
            // We understood the payload but nothing configured matches this repo/branch push.
            log.info("Webhook ignored: no configured repository matches the push (branch={}, repos={}).",
                    payload.branch(), payload.repositoryUrls());
            return ResponseEntity.accepted().body("ignored: no configured repository matches this push");
        }

        // Fallback: an unparseable payload reloads everything (unchanged legacy behaviour).
        var toImport = selected.isEmpty() ? repositories : selected;

        CompletableFuture.runAsync(() -> {
            log.info("Webhook ({}) triggered: importing {} repository/ies…", webhookProvider, toImport.size());
            try {
                var result = importUseCase.handle(toImport);
                if (!result.imported().isEmpty()) {
                    log.info("Webhook import: {} definition(s) imported: {}", result.imported().size(), result.imported());
                }
                if (!result.pruned().isEmpty()) {
                    log.info("Webhook import: {} definition(s) pruned (archived): {}", result.pruned().size(), result.pruned());
                }
                if (!result.errors().isEmpty()) {
                    log.warn("Webhook import: {} error(s): {}", result.errors().size(), result.errors());
                }
            } catch (Exception e) {
                log.error("Webhook import failed: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().body("import scheduled for " + toImport.size() + " repository/ies");
    }

    /**
     * The configured repositories the push applies to: URL matches (when the payload names a
     * repo) AND branch matches (when the payload names a branch). An empty result with a
     * non-empty payload means "understood but irrelevant"; an empty result with an empty
     * payload means "could not tell" and the caller reloads everything.
     */
    private List<GitImportProperties.GitRepository> selectRepositories(
            List<GitImportProperties.GitRepository> repositories, GitPushPayload payload) {
        if (payload.isEmpty()) {
            return List.of();
        }
        return repositories.stream()
                .filter(repo -> matchesRepository(repo, payload))
                .filter(repo -> matchesBranch(repo, payload))
                .toList();
    }

    private boolean matchesRepository(GitImportProperties.GitRepository repo, GitPushPayload payload) {
        if (payload.repositoryUrls() == null || payload.repositoryUrls().isEmpty()) {
            return true; // payload did not name a repo → don't exclude on that basis
        }
        return payload.repositoryUrls().stream()
                .anyMatch(url -> RepositoryUrlMatcher.sameRepository(url, repo.getUrl()));
    }

    private boolean matchesBranch(GitImportProperties.GitRepository repo, GitPushPayload payload) {
        if (payload.branch() == null || payload.branch().isBlank()) {
            return true; // payload did not name a branch → don't exclude on that basis
        }
        return payload.branch().equals(repo.getBranch());
    }
}
