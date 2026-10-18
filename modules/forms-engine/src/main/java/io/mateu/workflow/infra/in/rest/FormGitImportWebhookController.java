package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.GitPushPayload;
import io.mateu.workflow.webhook.GitPushPayloadParser;
import io.mateu.workflow.webhook.RepositoryUrlMatcher;
import io.mateu.workflow.webhook.UnsignedWebhookPolicy;
import io.mateu.workflow.webhook.WebhookBodies;
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

/**
 * Webhook endpoint triggered by a git provider after a push/merge.
 * {@code POST /forms/webhooks/{provider}} — re-imports the matching configured repositories.
 *
 * <p>{@code provider} is one of {@code github}, {@code gitlab}, {@code bitbucket} or
 * {@code generic}. The payload is parsed to reload only the repository and branch that
 * changed; a push to a repository/branch nothing is configured for is acknowledged and
 * ignored. {@code /github} keeps the original behaviour.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/forms/webhooks")
@RequiredArgsConstructor
@Slf4j
public class FormGitImportWebhookController {

    final GitImportProperties gitImportProperties;
    final ImportFormsFromGitUseCase importUseCase;
    /**
     * One import at a time, a repeat coalesced: two imports of the same source at once race, and the
     * stale one finishing last would prune what the newer one added.
     */
    io.mateu.workflow.webhook.CoalescingImportRunner importRunner = new io.mateu.workflow.webhook.CoalescingImportRunner();

    /**
     * Whether an unsigned webhook (no {@code webhook-secret}) may come in: see
     * {@link UnsignedWebhookPolicy}. False until the application context says otherwise.
     */
    java.util.function.BooleanSupplier unsignedAllowed = () -> false;

    /** The most of a body that is read ({@code workflow.rest.max-body-bytes}). */
    long maxBodyBytes = 16_777_216;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void webhookAccess(org.springframework.core.env.Environment environment,
                       org.springframework.beans.factory.ListableBeanFactory beans) {
        this.maxBodyBytes = environment.getProperty("workflow.rest.max-body-bytes", Long.class, 16_777_216L);
        this.unsignedAllowed = () -> environment.getProperty(UnsignedWebhookPolicy.ALLOW_ANONYMOUS, Boolean.class, false)
                || UnsignedWebhookPolicy.filterChainPresent(getClass().getClassLoader(),
                        type -> beans.getBeanNamesForType(type, false, false).length > 0);
    }

    @PostMapping("/{provider}")
    public ResponseEntity<String> webhook(
            @PathVariable String provider,
            @RequestHeader HttpHeaders headers,
            jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] body;
        try {
            body = WebhookBodies.read(request.getInputStream(), request.getContentLengthLong(), maxBodyBytes);
        } catch (WebhookBodies.TooLargeException e) {
            log.warn("Refused webhook {}: {}", request.getRequestURI(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
        return webhook(provider, headers, body);
    }

    /** The webhook, its body already read. */
    ResponseEntity<String> webhook(String provider, HttpHeaders headers, byte[] body) {

        var webhookProvider = WebhookProvider.fromPath(provider);
        var payloadBytes = body == null ? new byte[0] : body;
        var repositories = gitImportProperties.getRepositories();

        try {
            // Nothing to import, nothing to guard: an unsigned call with no repositories does nothing.
            var unsignedRefusal = repositories.isEmpty() || unsignedAllowed.getAsBoolean() ? null
                    : UnsignedWebhookPolicy.refusal("forms.git-import.webhook-secret");
            WebhookSignatureVerifier.verify(webhookProvider, gitImportProperties.getWebhookSecret(),
                    headers::getFirst, payloadBytes, unsignedRefusal);
        } catch (WebhookVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        if (repositories.isEmpty()) {
            log.info("Webhook received but no Git repositories configured — nothing to import.");
            return ResponseEntity.accepted().body("no repositories configured");
        }

        var payload = GitPushPayloadParser.parse(webhookProvider, payloadBytes);
        var selected = selectRepositories(repositories, payload);

        if (selected.isEmpty() && !payload.isEmpty()) {
            log.info("Webhook ignored: no configured repository matches the push (branch={}, repos={}).",
                    payload.branch(), payload.repositoryUrls());
            return ResponseEntity.accepted().body("ignored: no configured repository matches this push");
        }

        var toImport = selected.isEmpty() ? repositories : selected;

        var key = toImport.stream().map(r -> r.getUrl() + "#" + r.getBranch()).toList().toString();
        importRunner.submit(key, () -> {
            log.info("Webhook ({}) triggered: importing forms from {} repository/ies…", webhookProvider, toImport.size());
            try {
                var result = importUseCase.handle(toImport);
                if (!result.imported().isEmpty()) {
                    log.info("Webhook import: {} form(s) imported: {}", result.imported().size(), result.imported());
                }
                if (!result.pruned().isEmpty()) {
                    log.info("Webhook import: {} form(s) pruned (deleted): {}", result.pruned().size(), result.pruned());
                }
                if (!result.errors().isEmpty()) {
                    log.warn("Webhook import: {} error(s): {}", result.errors().size(), result.errors());
                }
            } catch (Exception e) {
                log.error("Webhook form import failed: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().body("import scheduled for " + toImport.size() + " repository/ies");
    }

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
            return true;
        }
        return payload.repositoryUrls().stream()
                .anyMatch(url -> RepositoryUrlMatcher.sameRepository(url, repo.getUrl()));
    }

    private boolean matchesBranch(GitImportProperties.GitRepository repo, GitPushPayload payload) {
        if (payload.branch() == null || payload.branch().isBlank()) {
            return true;
        }
        return payload.branch().equals(repo.getBranch());
    }
}
