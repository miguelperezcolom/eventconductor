package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
 * POST /rules/webhooks/github  →  re-imports all rule definitions from configured Git repositories.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/rules/webhooks")
@RequiredArgsConstructor
@Slf4j
public class RuleGitImportWebhookController {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    final RuleGitImportProperties gitImportProperties;
    final ImportRulesFromGitUseCase importUseCase;

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
            log.info("Webhook triggered: starting rule import from {} repository/ies…",
                    gitImportProperties.getRepositories().size());
            try {
                var result = importUseCase.handle();
                if (!result.imported().isEmpty()) {
                    log.info("Webhook import: {} rule(s) imported: {}", result.imported().size(), result.imported());
                }
                if (!result.errors().isEmpty()) {
                    log.warn("Webhook import: {} error(s): {}", result.errors().size(), result.errors());
                }
            } catch (Exception e) {
                log.error("Webhook rule import failed: {}", e.getMessage(), e);
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
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(received), HexFormat.of().parseHex(expected))) {
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
