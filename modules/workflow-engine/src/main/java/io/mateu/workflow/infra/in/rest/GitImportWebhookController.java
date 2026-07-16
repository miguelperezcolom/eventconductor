package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.infra.config.GitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * Webhook endpoint triggered by GitHub (or any compatible system) after a push/merge.
 * POST /workflow/webhooks/github  →  re-imports all configured Git repositories.
 *
 * Configure in application.yml:
 * <pre>
 *   workflow:
 *     git-import:
 *       webhook-secret: mysecret   # optional — set in GitHub repo settings
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

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    final GitImportProperties gitImportProperties;
    final ImportWorkflowDefinitionsFromGitUseCase importUseCase;

    /**
     * GitHub webhook receiver. Responds 202 immediately and runs the import in the background
     * so GitHub's 10-second delivery timeout is never hit, even for large repos.
     */
    @PostMapping("/github")
    public ResponseEntity<String> githubWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody byte[] body) {

        verifySignature(body, signatureHeader);

        if (gitImportProperties.getRepositories().isEmpty()) {
            log.info("Webhook received but no Git repositories configured — nothing to import.");
            return ResponseEntity.accepted().body("no repositories configured");
        }

        CompletableFuture.runAsync(() -> {
            log.info("Webhook triggered: starting workflow definition import from {} repository/ies…",
                    gitImportProperties.getRepositories().size());
            try {
                var result = importUseCase.handle();
                if (!result.imported().isEmpty()) {
                    log.info("Webhook import: {} definition(s) imported: {}", result.imported().size(), result.imported());
                }
                if (!result.errors().isEmpty()) {
                    log.warn("Webhook import: {} error(s): {}", result.errors().size(), result.errors());
                }
            } catch (Exception e) {
                log.error("Webhook import failed: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().body("import scheduled");
    }

    private void verifySignature(byte[] body, String signatureHeader) {
        String secret = gitImportProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing or malformed X-Hub-Signature-256 header");
        }
        String received = signatureHeader.substring(7);
        String expected = hmacSha256Hex(secret, body);
        byte[] receivedBytes;
        try {
            receivedBytes = HexFormat.of().parseHex(received);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        // constant-time comparison to prevent timing attacks
        if (!MessageDigest.isEqual(receivedBytes, HexFormat.of().parseHex(expected))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
    }

    private static String hmacSha256Hex(String secret, byte[] data) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}
